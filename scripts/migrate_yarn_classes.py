"""Convert 1.18.2 Yarn class references to Mojang class references.

Inputs are downloaded mappings in /tmp. This handles classes only; member
and runtime API changes require compiler-guided repairs.
"""
import pathlib, re, json
root=pathlib.Path(__file__).resolve().parents[1]
obf_to_inter={}
for line in pathlib.Path('/tmp/altoclef-port-1.18.2-intermediary.tiny').read_text().splitlines():
    p=line.split('\t')
    if p[0]=='c': obf_to_inter[p[1]]=p[2]
inter_to_named={}
for line in pathlib.Path('/tmp/altoclef-port-1.18.2-yarn.tiny').read_text().splitlines():
    p=line.split('\t')
    if p[0]=='c': inter_to_named[p[1]]=p[2].replace('/','.').replace('$','.')
classes={}
for line in pathlib.Path('/tmp/altoclef-port-1.18.2-mojmap.txt').read_text().splitlines():
    if line and not line[0].isspace() and not line.startswith('#') and ' -> ' in line:
        named,obf=line.removesuffix(':').split(' -> ')
        yarn=inter_to_named.get(obf_to_inter.get(obf))
        if yarn: classes[yarn]=named.replace('$','.')
changes=[]
for path in sorted((root/'src/main/java').rglob('*.java')):
    text=path.read_text(); original=text
    imports=re.findall(r'^import (net\.minecraft\.[^;]+);',text,re.M)
    selected={}
    for imp in imports:
        if imp.endswith('.*'):
            package=imp[:-2]
            for old,new in classes.items():
                simple=old.rsplit('.',1)[-1]
                if old.rsplit('.',1)[0]==package and re.search(r'\b'+re.escape(simple)+r'\b',text): selected[old]=new
        elif imp in classes: selected[imp]=classes[imp]
    for imp in imports:
        if imp.endswith('.*'):
            repl='\n'.join('import '+new+';' for old,new in sorted(selected.items()) if old.rsplit('.',1)[0]==imp[:-2])
            text=text.replace('import '+imp+';',repl)
        elif imp in selected: text=text.replace('import '+imp+';','import '+selected[imp]+';')
    simples={old.rsplit('.',1)[-1]:new.rsplit('.',1)[-1] for old,new in selected.items()}
    # One substitution pass prevents names from being converted twice.
    if simples:
        pattern=r'\b('+ '|'.join(re.escape(s) for s in sorted(simples,key=len,reverse=True))+r')\b'
        text=re.sub(pattern,lambda m:simples[m[0]],text)
    # Fully qualified references outside imports.
    for old,new in sorted(classes.items(),key=lambda x:len(x[0]),reverse=True):
        if old in text: text=re.sub(re.escape(old)+r'\b',new,text)
    if text!=original:
        path.write_text(text); changes.append(str(path.relative_to(root)))
(root/'.audit/class-migration.json').write_text(json.dumps({'changed_files':changes,'class_mapping_count':len(classes)},indent=2)+'\n')
print('Converted',len(changes),'files with',len(classes),'class mappings')
