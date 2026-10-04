"""Apply mapping-backed member renames only at compiler-reported failures."""
import pathlib,re,json,sys,collections
root=pathlib.Path(__file__).resolve().parents[1]
map=json.loads((root/'.audit/exact-member-map.json').read_text())
globalmap=json.loads((root/'.audit/exact-global-map.json').read_text())
log=(root/sys.argv[1]).read_text()
# Verified mappings whose overloaded or inherited declaration isn't in diagnostics.
methods={'squaredDistanceTo':'distanceToSqr','getSquaredDistance':'distSqr','isOnGround':'onGround','getVelocity':'getDeltaMovement','setVelocity':'setDeltaMovement','isTouchingWater':'isInWater','getNetworkHandler':'getConnection','getHungerManager':'getFoodData','getHunger':'getFoodLevel','getStack':'getItem','getTranslationKey':'getDescriptionId','hasStatusEffect':'hasEffect','getAttackCooldownProgress':'getAttackStrengthScale','getArmor':'getArmorValue','raycast':'clip','dotProduct':'dot','lengthSquared':'lengthSqr','getCameraPosVec':'getEyePosition','isClimbing':'onClimbable','getArmorStack':'getArmorItem','getMaxCount':'getMaxStackSize','getBlockFromItem':'byItem','getDefaultState':'defaultBlockState','isToolRequired':'requiresCorrectToolForDrops','getMiningSpeedMultiplier':'getDestroySpeed','isSuitableFor':'isCorrectToolForDrops','getStartX':'getMinBlockX','getEndX':'getMaxBlockX','getStartZ':'getMinBlockZ','getEndZ':'getMaxBlockZ','getDimension':'dimensionType','getRotationVec':'getViewVector','getEyeY':'getEyeY','getY':'getY','getX':'getX','getZ':'getZ','isInRange':'closerThan','getMainHandStack':'getMainHandItem','getOffHandStack':'getOffhandItem','getActiveItem':'getUseItem','setPressed':'setDown','getScreenHandler':'getMenu','getCooldown':'getCooldown','getFoodComponent':'getFoodProperties','getFluidState':'getFluidState','isSneaking':'isShiftKeyDown','isSubmergedInWater':'isUnderWater','getBoundingBox':'getBoundingBox','getName':'getName','getTimeOfDay':'getDayTime','getTime':'getGameTime','getRegistryKey':'dimension','getEntities':'entitiesForRendering','getEntityById':'getEntity','getBottomY':'getMinY','getTopY':'getMaxY'}
methods.update({'getBlockPos':'blockPosition','isWithinDistance':'closerToCenterThan','isUltrawarm':'ultraWarm','isNatural':'natural','setYaw':'setYRot','setPitch':'setXRot','swingHand':'swing','getNutrition':'nutrition','getSaturationModifier':'saturation','getNormal':'getUnitVec3i','getVector':'getUnitVec3i','getBlockFromItem':'byItem','getWidth':'width','getSquaredDistance':'distanceToSqr','getHunger':'nutrition'})
fields={'world':'level','currentScreenHandler':'containerMenu','playerScreenHandler':'inventoryMenu','currentScreen':'screen','interactionManager':'gameMode','crosshairTarget':'hitResult','textRenderer':'font','syncId':'containerId','selectedSlot':'selected','client':'minecraft','horizontalCollision':'horizontalCollision','forwardSpeed':'zza','sidewaysSpeed':'xxa','handSwinging':'swinging','jumping':'jumping','GRASS':'SHORT_GRASS','ITEM':'ITEM','BLOCK':'BLOCK'}
changed=collections.Counter(); files={}; unresolved=collections.Counter()
pat=r'([^:\n]*?/src/main/java/[^:\n]+):(\d+): error: ([^\n]+)\n(.*?)(?=\n[^\n]*?/src/main/java/|\n\s*\d[\d,]* errors|\Z)'
for m in re.finditer(pat,log,re.S):
 path=pathlib.Path(m[1]);rel=str(path.relative_to(root));line=int(m[2])-1;body=m[4]
 if '/mixins/' in rel or '/eventbus/events/' in rel or '/ui/' in rel or '/baritone/' in rel or '/schematic/' in rel:continue
 sym=re.search(r'symbol:\s+(method|variable) (\w+)',body)
 loc=re.search(r'location:\s+(?:variable \w+ of type |class |interface )(\w+)',body)
 if not sym or not loc:continue
 kind,old=sym.groups();owner=loc[1];key=('m' if kind=='method' else 'f')+'#'+old
 targets=map.get(owner,{}).get(key,[])
 target=targets[0] if len(targets)==1 else None
 if old == 'getPos':
  target={'Vec3':'position','ItemEntity':'position','LocalPlayer':'position','Entity':'position','FishingHook':'position','ThrownEnderpearl':'position','AbstractArrow':'position','ThrownExperienceBottle':'position','LivingEntity':'position'}.get(owner) if old=='getPos' else None
 if not target:
  if kind=='method':target=methods.get(old)
  else:target=fields.get(old)
 if not target or target==old:unresolved[(owner,old)]+=1;continue
 lines=files.setdefault(path,path.read_text().splitlines(keepends=True))
 code=body.splitlines()[0].strip() if body.splitlines() else ''
 if line>=len(lines) or lines[line].strip()!=code:
  candidates=[i for i,l in enumerate(lines) if l.strip()==code]
  if len(candidates)!=1:continue
  line=candidates[0]
 pattern=r'\b'+re.escape(old)+r'\b'+(r'(?=\s*\()' if kind=='method' else '')
 lines[line],n=re.subn(pattern,target,lines[line]);changed[(old,target)]+=n
for path,lines in files.items():path.write_text(''.join(lines))
print('Repaired',sum(changed.values()),'names in',len(files),'files')
print('Changed:',changed.most_common(25))
print('Unresolved:',unresolved.most_common(35))
