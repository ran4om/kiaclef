import * as THREE from "three";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { UnrealBloomPass } from "three/addons/postprocessing/UnrealBloomPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";

const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const isSmall = window.matchMedia("(max-width: 900px)").matches;
const gsap = window.gsap;
const ScrollTrigger = window.ScrollTrigger;
if (gsap && ScrollTrigger) gsap.registerPlugin(ScrollTrigger);

/* ------------------------------------------------------------------ */
/* Deterministic helpers                                               */
/* ------------------------------------------------------------------ */
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(26026);
function hash2(x, z) {
  const s = Math.sin(x * 127.1 + z * 311.7) * 43758.5453;
  return s - Math.floor(s);
}
function noise2(x, z) {
  const xi = Math.floor(x), zi = Math.floor(z);
  const xf = x - xi, zf = z - zi;
  const u = xf * xf * (3 - 2 * xf), v = zf * zf * (3 - 2 * zf);
  const a = hash2(xi, zi), b = hash2(xi + 1, zi), c = hash2(xi, zi + 1), d = hash2(xi + 1, zi + 1);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}
const smooth = (t) => t * t * (3 - 2 * t);
const clamp01 = (t) => Math.min(1, Math.max(0, t));
const lerp = (a, b, t) => a + (b - a) * t;

/* ------------------------------------------------------------------ */
/* Pixel textures (procedural, 16x16, original art)                    */
/* ------------------------------------------------------------------ */
function pixelTexture(draw, size = 16) {
  const c = document.createElement("canvas");
  c.width = c.height = size;
  const g = c.getContext("2d");
  const r = mulberry32(Math.floor(rand() * 1e9));
  draw(g, r, size);
  const t = new THREE.CanvasTexture(c);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.colorSpace = THREE.SRGBColorSpace;
  t.generateMipmaps = false;
  return t;
}
function shade(hex, f) {
  const c = new THREE.Color(hex);
  c.offsetHSL(0, 0, f);
  return "#" + c.getHexString();
}
function speckle(base, spread) {
  return (g, r, s) => {
    for (let y = 0; y < s; y++) for (let x = 0; x < s; x++) {
      g.fillStyle = shade(base, (r() - 0.5) * spread);
      g.fillRect(x, y, 1, 1);
    }
  };
}
function ore(color, glow = false) {
  return (g, r, s) => {
    speckle("#74777a", 0.12)(g, r, s);
    for (let i = 0; i < 5; i++) {
      const cx = 2 + Math.floor(r() * 11), cy = 2 + Math.floor(r() * 11);
      for (let k = 0; k < 4; k++) {
        g.fillStyle = shade(color, (r() - 0.3) * (glow ? 0.12 : 0.2));
        g.fillRect(cx + Math.floor(r() * 3) - 1, cy + Math.floor(r() * 3) - 1, 1 + Math.floor(r() * 2), 1);
      }
    }
  };
}
const TEX = {
  stone: pixelTexture(speckle("#74777a", 0.12)),
  dirt: pixelTexture(speckle("#6b4a31", 0.14)),
  grassTop: pixelTexture(speckle("#4f8d3a", 0.16)),
  grassSide: pixelTexture((g, r, s) => {
    speckle("#6b4a31", 0.14)(g, r, s);
    for (let x = 0; x < s; x++) {
      const h = 3 + Math.floor(r() * 3);
      for (let y = 0; y < h; y++) { g.fillStyle = shade("#4f8d3a", (r() - 0.5) * 0.16); g.fillRect(x, y, 1, 1); }
    }
  }),
  coal: pixelTexture(ore("#1d1f22")),
  iron: pixelTexture(ore("#d7a27a")),
  diamond: pixelTexture(ore("#56f0e0", true)),
  diamondGlow: pixelTexture((g, r, s) => {
    g.fillStyle = "#000"; g.fillRect(0, 0, s, s);
    ore("#56f0e0", true)(g, mulberry32(7), s);
    const d = g.getImageData(0, 0, s, s);
    for (let i = 0; i < d.data.length; i += 4) {
      const isOre = d.data[i + 1] > 150 && d.data[i + 2] > 150;
      if (!isOre) { d.data[i] = d.data[i + 1] = d.data[i + 2] = 0; }
    }
    g.putImageData(d, 0, 0);
  }),
  logSide: pixelTexture((g, r, s) => {
    for (let x = 0; x < s; x++) {
      const b = (r() - 0.5) * 0.1;
      for (let y = 0; y < s; y++) { g.fillStyle = shade("#5b3d24", b + (r() - 0.5) * 0.08 + (x % 4 === 0 ? -0.06 : 0)); g.fillRect(x, y, 1, 1); }
    }
  }),
  logTop: pixelTexture((g, r, s) => {
    for (let y = 0; y < s; y++) for (let x = 0; x < s; x++) {
      const d = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5));
      g.fillStyle = d > 6.5 ? shade("#5b3d24", (r() - 0.5) * 0.1) : shade("#a8814f", (Math.floor(d) % 2 ? -0.06 : 0.02) + (r() - 0.5) * 0.04);
      g.fillRect(x, y, 1, 1);
    }
  }),
  leaves: pixelTexture((g, r, s) => {
    for (let y = 0; y < s; y++) for (let x = 0; x < s; x++) {
      g.fillStyle = r() < 0.18 ? "#1d3a1c" : shade("#3b7a33", (r() - 0.5) * 0.2);
      g.fillRect(x, y, 1, 1);
    }
  }),
  planks: pixelTexture((g, r, s) => {
    for (let y = 0; y < s; y++) for (let x = 0; x < s; x++) {
      const seam = y % 4 === 3 || (x === ((Math.floor(y / 4) * 7) % 16));
      g.fillStyle = seam ? "#6f5030" : shade("#a87b46", (r() - 0.5) * 0.08);
      g.fillRect(x, y, 1, 1);
    }
  }),
  cobble: pixelTexture((g, r, s) => {
    speckle("#6e7073", 0.2)(g, r, s);
    g.fillStyle = "#3f4144";
    for (let i = 0; i < 18; i++) g.fillRect(Math.floor(r() * s), Math.floor(r() * s), 2, 1);
  }),
  tableTop: pixelTexture((g, r, s) => {
    for (let y = 0; y < s; y++) for (let x = 0; x < s; x++) {
      const grid = x % 5 === 0 || y % 5 === 0;
      g.fillStyle = grid ? "#4a321d" : shade("#b18651", (r() - 0.5) * 0.08);
      g.fillRect(x, y, 1, 1);
    }
  }),
  furnaceFront: pixelTexture((g, r, s) => {
    speckle("#6e7073", 0.2)(g, r, s);
    g.fillStyle = "#16171a"; g.fillRect(4, 8, 8, 6);
    g.fillStyle = "#ff8a2a"; g.fillRect(5, 11, 6, 2);
    g.fillStyle = "#ffd36b"; g.fillRect(6, 12, 4, 1);
  }),
  furnaceGlow: pixelTexture((g) => {
    g.fillStyle = "#000"; g.fillRect(0, 0, 16, 16);
    g.fillStyle = "#ff8a2a"; g.fillRect(5, 11, 6, 2);
    g.fillStyle = "#ffd36b"; g.fillRect(6, 12, 4, 1);
  }),
};

function mat(map, opts = {}) {
  return new THREE.MeshStandardMaterial({ map, roughness: 0.95, metalness: 0, ...opts });
}
const MAT = {
  stone: mat(TEX.stone),
  dirt: mat(TEX.dirt),
  grass: [mat(TEX.grassSide), mat(TEX.grassSide), mat(TEX.grassTop), mat(TEX.dirt), mat(TEX.grassSide), mat(TEX.grassSide)],
  coal: mat(TEX.coal),
  iron: mat(TEX.iron),
  diamond: mat(TEX.diamond, { emissive: new THREE.Color("#56f0e0"), emissiveMap: TEX.diamondGlow, emissiveIntensity: 1.0 }),
  log: [mat(TEX.logSide), mat(TEX.logSide), mat(TEX.logTop), mat(TEX.logTop), mat(TEX.logSide), mat(TEX.logSide)],
  leaves: mat(TEX.leaves),
  planks: mat(TEX.planks),
  cobble: mat(TEX.cobble),
  table: [mat(TEX.planks), mat(TEX.planks), mat(TEX.tableTop), mat(TEX.planks), mat(TEX.planks), mat(TEX.planks)],
  furnace: [mat(TEX.cobble), mat(TEX.cobble), mat(TEX.cobble), mat(TEX.cobble),
    mat(TEX.furnaceFront, { emissive: new THREE.Color("#ff8a2a"), emissiveMap: TEX.furnaceGlow, emissiveIntensity: 0 }), mat(TEX.cobble)],
};

/* ------------------------------------------------------------------ */
/* Item icons for the inventory section (rendered once, offscreen)     */
/* ------------------------------------------------------------------ */
function renderItemIcons() {
  const size = 96;
  const r = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
  r.setSize(size, size);
  r.outputColorSpace = THREE.SRGBColorSpace;
  const cam = new THREE.OrthographicCamera(-1.1, 1.1, 1.1, -1.1, 0.1, 10);
  cam.position.set(2.2, 1.8, 2.2); cam.lookAt(0, 0, 0);
  const lightScene = (s) => {
    s.add(new THREE.AmbientLight(0xffffff, 1.1));
    const d = new THREE.DirectionalLight(0xffffff, 2.2); d.position.set(1, 2, 1.4); s.add(d);
  };
  const box = (m, sx = 1, sy = 1, sz = 1) => new THREE.Mesh(new THREE.BoxGeometry(sx, sy, sz), m);
  const solid = (hex, em) => new THREE.MeshStandardMaterial({ color: hex, roughness: 0.6, emissive: em ? hex : 0x000000, emissiveIntensity: em ? 0.35 : 0 });
  const makers = {
    log: () => box(MAT.log),
    planks: () => box(MAT.planks),
    table: () => box(MAT.table),
    cobble: () => box(MAT.cobble),
    chest: () => {
      const g = new THREE.Group();
      g.add(box(solid(0xa8712f), 0.95, 0.85, 0.95));
      const band = box(solid(0x3b2410), 0.97, 0.08, 0.97); band.position.y = 0.12; g.add(band);
      const latch = box(solid(0xd8d8d0), 0.16, 0.22, 0.05); latch.position.set(0, 0.08, 0.5); g.add(latch);
      return g;
    },
    stick: () => {
      const g = new THREE.Group();
      const s = box(solid(0x8a6136), 0.16, 1.6, 0.16); s.rotation.z = Math.PI / 4; s.rotation.x = 0.3; g.add(s);
      return g;
    },
    torch: () => {
      const g = new THREE.Group();
      const s = box(solid(0x8a6136), 0.16, 1.1, 0.16); s.position.y = -0.2; g.add(s);
      const f = box(solid(0xffc14d, true), 0.24, 0.26, 0.24); f.position.y = 0.45; g.add(f);
      g.rotation.z = 0.35;
      return g;
    },
    coal: () => {
      const g = new THREE.Group();
      [[0, 0, 0], [0.35, 0.2, 0], [-0.3, 0.25, 0.2], [0.1, -0.3, 0.25]].forEach(([x, y, z]) => {
        const b = box(solid(0x23252a), 0.5, 0.5, 0.5); b.position.set(x, y, z); b.rotation.set(x, y, z); g.add(b);
      });
      return g;
    },
    iron: () => {
      const g = new THREE.Group();
      const b = box(solid(0xd9dcdd), 1.3, 0.35, 0.6); b.rotation.y = 0.5; g.add(b);
      return g;
    },
    diamond: () => {
      const g = new THREE.Group();
      const m = new THREE.Mesh(new THREE.OctahedronGeometry(0.85, 0), solid(0x56f0e0, true));
      m.rotation.y = 0.5; g.add(m);
      return g;
    },
  };
  const icons = {};
  for (const [name, make] of Object.entries(makers)) {
    const s = new THREE.Scene(); lightScene(s);
    const o = make(); s.add(o);
    r.render(s, cam);
    icons[name] = r.domElement.toDataURL("image/png");
  }
  r.dispose();
  return icons;
}

/* ------------------------------------------------------------------ */
/* World                                                               */
/* ------------------------------------------------------------------ */
function buildIsland(R, seed, opts = {}) {
  const blocks = new Map(); // "x,y,z" -> type
  const key = (x, y, z) => x + "," + y + "," + z;
  const cut = opts.cut ?? Infinity;
  for (let x = -R; x <= R; x++) for (let z = -R; z <= R; z++) {
    const d = Math.hypot(x, z) / R;
    const edge = 0.82 + noise2(x * 0.23 + seed, z * 0.23) * 0.3;
    if (d > edge) continue;
    if (z > cut) continue;
    const top = Math.round(1 + noise2(x * 0.18 + seed, z * 0.18 + 9) * 2.4 - d * 1.5);
    const depth = Math.round(Math.pow(1 - d / edge, 0.7) * (opts.depth ?? 15) + noise2(x * 0.4, z * 0.4 + seed) * 2.5);
    for (let y = top - depth; y <= top; y++) {
      let type = "stone";
      if (y === top) type = "grass";
      else if (y > top - 3) type = "dirt";
      blocks.set(key(x, y, z), type);
    }
  }
  return { blocks, key };
}

function addTree(blocks, key, x, y, z, h) {
  for (let i = 1; i <= h; i++) blocks.set(key(x, y + i, z), "log");
  for (let dx = -2; dx <= 2; dx++) for (let dz = -2; dz <= 2; dz++) for (let dy = -1; dy <= 1; dy++) {
    if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) > 3) continue;
    const k = key(x + dx, y + h + dy, z + dz);
    if (!blocks.has(k)) blocks.set(k, "leaves");
  }
  blocks.set(key(x, y + h + 2, z), "leaves");
}

function topAt(blocks, x, z) {
  for (let y = 12; y > -30; y--) if (blocks.has(x + "," + y + "," + z)) return y;
  return null;
}

function instancedFromBlocks(blocks, scene, { shadows, skipHidden = true } = {}) {
  const byType = {};
  const dirs = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  for (const [k, type] of blocks) {
    const [x, y, z] = k.split(",").map(Number);
    if (skipHidden && type !== "leaves") {
      let exposed = false;
      for (const [dx, dy, dz] of dirs) {
        const n = blocks.get((x + dx) + "," + (y + dy) + "," + (z + dz));
        if (!n || n === "leaves") { exposed = true; break; }
      }
      if (!exposed) continue;
    }
    (byType[type] ||= []).push([x, y, z]);
  }
  const geo = new THREE.BoxGeometry(1, 1, 1);
  const m4 = new THREE.Matrix4();
  const meshes = {};
  for (const [type, list] of Object.entries(byType)) {
    const mesh = new THREE.InstancedMesh(geo, MAT[type], list.length);
    list.forEach(([x, y, z], i) => { m4.makeTranslation(x, y, z); mesh.setMatrixAt(i, m4); });
    mesh.castShadow = shadows; mesh.receiveShadow = shadows;
    scene.add(mesh);
    meshes[type] = mesh;
  }
  return meshes;
}

function makeRobot() {
  const g = new THREE.Group();
  const shell = new THREE.MeshStandardMaterial({ color: 0xdfe6e3, roughness: 0.45, metalness: 0.1 });
  const dark = new THREE.MeshStandardMaterial({ color: 0x2a3035, roughness: 0.6 });
  const visor = new THREE.MeshStandardMaterial({ color: 0x0b1512, emissive: 0x5fd1a5, emissiveIntensity: 2.4 });
  const body = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.66, 0.42), shell); body.position.y = 0.78; g.add(body);
  const head = new THREE.Mesh(new THREE.BoxGeometry(0.66, 0.6, 0.6), shell); head.position.y = 1.44; g.add(head);
  const face = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.26, 0.02), visor); face.position.set(0, 1.46, 0.31); g.add(face);
  const ant = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.22, 0.05), dark); ant.position.set(0.18, 1.84, 0); g.add(ant);
  const tip = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.1, 0.1), visor); tip.position.set(0.18, 1.98, 0); g.add(tip);
  for (const sx of [-0.15, 0.15]) {
    const leg = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.46, 0.24), dark); leg.position.set(sx, 0.23, 0); g.add(leg);
  }
  const armPivot = new THREE.Group(); armPivot.position.set(0.42, 1.02, 0); g.add(armPivot);
  const arm = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.5, 0.16), shell); arm.position.y = -0.22; armPivot.add(arm);
  const handle = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.06, 0.7), new THREE.MeshStandardMaterial({ color: 0x7a5530 }));
  handle.position.set(0, -0.46, 0.28); armPivot.add(handle);
  const pickMat = new THREE.MeshStandardMaterial({ color: 0x8fe9ff, emissive: 0x2aa9c9, emissiveIntensity: 0.4, roughness: 0.3 });
  const pick = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.08, 0.08), pickMat); pick.position.set(0, -0.46, 0.62); armPivot.add(pick);
  const other = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.5, 0.16), shell); other.position.set(-0.42, 0.8, 0); g.add(other);
  g.traverse((o) => { if (o.isMesh) { o.castShadow = true; } });
  g.userData = { armPivot, pickMat };
  return g;
}

function skyMaterial(sunDir) {
  return new THREE.ShaderMaterial({
    side: THREE.BackSide, depthWrite: false,
    uniforms: { sunDir: { value: sunDir } },
    vertexShader: `varying vec3 vDir; void main(){ vDir = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }`,
    fragmentShader: `
      varying vec3 vDir; uniform vec3 sunDir;
      void main(){
        float h = clamp(vDir.y, -0.2, 1.0);
        vec3 zenith = vec3(0.03, 0.05, 0.11);
        vec3 mid = vec3(0.07, 0.10, 0.22);
        vec3 horizon = vec3(0.42, 0.20, 0.22);
        float sun = max(dot(normalize(vec3(vDir.x, 0.0, vDir.z)), normalize(vec3(sunDir.x, 0.0, sunDir.z))), 0.0);
        vec3 warm = mix(horizon, vec3(0.95, 0.45, 0.22), pow(sun, 6.0));
        vec3 col = mix(mix(warm, mid, smoothstep(0.0, 0.22, h)), zenith, smoothstep(0.22, 0.9, h));
        col += vec3(1.0, 0.55, 0.25) * pow(max(dot(vDir, normalize(sunDir)), 0.0), 220.0) * 2.0;
        col = mix(col, vec3(0.04,0.06,0.10), smoothstep(0.0, -0.2, vDir.y));
        gl_FragColor = vec4(col, 1.0);
      }`,
  });
}

function waterMaterial(sunDir) {
  return new THREE.ShaderMaterial({
    transparent: false,
    uniforms: { time: { value: 0 }, sunDir: { value: sunDir }, camPos: { value: new THREE.Vector3() } },
    vertexShader: `varying vec3 vWorld; void main(){ vec4 w = modelMatrix * vec4(position,1.0); vWorld = w.xyz; gl_Position = projectionMatrix * viewMatrix * w; }`,
    fragmentShader: `
      varying vec3 vWorld; uniform float time; uniform vec3 sunDir; uniform vec3 camPos;
      float h(vec2 p){ return fract(sin(dot(p, vec2(127.1,311.7)))*43758.5453); }
      float n(vec2 p){ vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
        return mix(mix(h(i),h(i+vec2(1,0)),f.x), mix(h(i+vec2(0,1)),h(i+vec2(1,1)),f.x), f.y); }
      void main(){
        vec2 p = vWorld.xz * 0.12;
        float w = n(p + time*0.05) * 0.6 + n(p*2.3 - time*0.08) * 0.4;
        vec3 view = normalize(camPos - vWorld);
        float fres = pow(1.0 - max(view.y, 0.0), 3.0);
        vec3 deep = vec3(0.012, 0.03, 0.05);
        vec3 sky = vec3(0.10, 0.12, 0.24);
        vec3 col = mix(deep, sky, fres * 0.8);
        vec2 toSun = normalize(sunDir.xz);
        float streak = pow(max(dot(normalize((vWorld.xz - camPos.xz)), toSun), 0.0), 40.0);
        col += vec3(1.0, 0.5, 0.22) * streak * smoothstep(0.55, 0.85, w) * 0.9;
        col += vec3(0.25, 0.85, 0.95) * smoothstep(0.82, 0.98, w) * 0.05;
        float fog = smoothstep(40.0, 160.0, length(vWorld.xz - camPos.xz));
        col = mix(col, vec3(0.06, 0.07, 0.13), fog);
        gl_FragColor = vec4(col, 1.0);
      }`,
  });
}

function initWorld(canvas) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: !isSmall, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, isSmall ? 1.5 : 1.75));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.45;
  const shadows = !isSmall;
  renderer.shadowMap.enabled = shadows;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const scene = new THREE.Scene();
  scene.fog = new THREE.FogExp2(0x101629, 0.0085);
  const camera = new THREE.PerspectiveCamera(isSmall ? 52 : 40, window.innerWidth / window.innerHeight, 0.1, 600);

  const sunDir = new THREE.Vector3(-0.85, 0.12, -0.5).normalize();
  const sky = new THREE.Mesh(new THREE.SphereGeometry(400, 32, 16), skyMaterial(sunDir));
  scene.add(sky);

  // Stars
  const starGeo = new THREE.BufferGeometry();
  const sp = [];
  for (let i = 0; i < 900; i++) {
    const t = rand() * Math.PI * 2, u = 0.12 + rand() * 0.88;
    const r = 380;
    sp.push(Math.cos(t) * Math.sqrt(1 - u * u) * r, u * r, Math.sin(t) * Math.sqrt(1 - u * u) * r);
  }
  starGeo.setAttribute("position", new THREE.Float32BufferAttribute(sp, 3));
  const stars = new THREE.Points(starGeo, new THREE.PointsMaterial({ color: 0xcfe0ff, size: 1.3, sizeAttenuation: false, transparent: true, opacity: 0.8, fog: false }));
  scene.add(stars);

  // Water
  const water = new THREE.Mesh(new THREE.PlaneGeometry(800, 800), waterMaterial(sunDir));
  water.rotation.x = -Math.PI / 2; water.position.y = -17;
  scene.add(water);

  // Lights
  scene.add(new THREE.HemisphereLight(0xb9c3e6, 0x1a1712, 1.5));
  const sun = new THREE.DirectionalLight(0xffb27a, 3.4);
  sun.position.copy(sunDir).multiplyScalar(60).add(new THREE.Vector3(0, 30, 0));
  sun.castShadow = shadows;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -24, right: 24, top: 24, bottom: -24, near: 1, far: 160 });
  sun.shadow.bias = -0.0006;
  scene.add(sun);
  const rim = new THREE.DirectionalLight(0x8fb4ff, 1.4);
  rim.position.set(30, 20, 40);
  scene.add(rim);
  const fill = new THREE.DirectionalLight(0xffc49a, 1.3);
  fill.position.set(-25, 12, 35);
  scene.add(fill);

  // Main island with a cut cliff face toward +z
  const CUT = 6;
  const { blocks, key } = buildIsland(14, 3.1, { cut: CUT, depth: 16 });
  // Ores exposed on the cliff face and scattered inside
  for (const [k, type] of [...blocks]) {
    if (type !== "stone") continue;
    const [x, y, z] = k.split(",").map(Number);
    const face = z === CUT || z === CUT - 1;
    const r = hash2(x * 3.7 + y, z * 1.3 - y);
    if (y >= -4 && y <= -1 && r < (face ? 0.16 : 0.04)) blocks.set(k, "coal");
    else if (y >= -9 && y <= -5 && r < (face ? 0.14 : 0.04)) blocks.set(k, "iron");
    else if (y <= -11 && r < (face ? 0.12 : 0.02)) blocks.set(k, "diamond");
  }
  // Pick feature ores from blocks that really exist on the cliff face, so shots always have subjects.
  const face = [...blocks.keys()].map((k) => k.split(",").map(Number)).filter(([, , z]) => z === CUT);
  const nearest = (want, filter) => face.filter(filter)
    .sort((a, b) => Math.hypot(a[0] - want[0], a[1] - want[1]) - Math.hypot(b[0] - want[0], b[1] - want[1]))[0];
  const featIron = nearest([4, -6], ([x, y]) => y <= -5 && y >= -8);
  const featDiamond = face.filter(([x]) => Math.abs(x) <= 3).sort((a, b) => a[1] - b[1])[1] ?? nearest([0, -11], () => true);
  const paint = (center, type, spots) => spots.forEach(([dx, dy]) => {
    const k = key(center[0] + dx, center[1] + dy, CUT);
    if (blocks.has(k)) blocks.set(k, type);
  });
  paint(featIron, "iron", [[0, 0], [1, 0], [0, -1], [1, 1], [-1, 0]]);
  paint(featDiamond, "diamond", [[0, 0], [1, 0], [-1, 0], [0, 1], [1, 1]]);
  // Carve a ledge for the robot on the face
  const ledge = [1, -3];
  for (let dx = -1; dx <= 2; dx++) for (let dy = 0; dy <= 2; dy++) blocks.delete(key(ledge[0] + dx, ledge[1] + dy, CUT));
  for (let dx = -1; dx <= 2; dx++) for (let dy = 0; dy <= 2; dy++) blocks.delete(key(ledge[0] + dx, ledge[1] + dy, CUT - 1));

  // Trees
  const treeSpots = [[-6, -4, 5], [5, -7, 4], [-3, -9, 5], [8, 1, 4], [-9, 2, 4]];
  for (const [x, z, h] of treeSpots) {
    const t = topAt(blocks, x, z); if (t !== null) addTree(blocks, key, x, t, z, h);
  }
  // Crafting table and furnace near the cliff edge
  const tablePos = [3, 0, 3]; tablePos[1] = topAt(blocks, 3, 3) + 1;
  const furnacePos = [5, 0, 2]; furnacePos[1] = topAt(blocks, 5, 2) + 1;
  blocks.set(key(...tablePos), "table");
  blocks.set(key(...furnacePos), "furnace");

  const meshes = instancedFromBlocks(blocks, scene, { shadows });

  // Floating debris islands in the distance
  const far = [[-60, -8, -70, 5], [70, -4, -60, 6], [-90, 2, 10, 4], [45, -10, 55, 4], [110, 4, -10, 5], [-40, 6, -120, 7]];
  for (const [x, y, z, r] of far) {
    const isl = buildIsland(r, x * 0.1, { depth: r * 1.6 });
    for (const [k] of isl.blocks) {
      if (rand() < 0.02 && isl.blocks.get(k) === "grass") {
        const [bx, by, bz] = k.split(",").map(Number);
        addTree(isl.blocks, isl.key, bx, by, bz, 3);
      }
    }
    const g = new THREE.Group(); g.position.set(x, y, z);
    instancedFromBlocks(isl.blocks, g, { shadows: false });
    scene.add(g);
  }

  // Robot on the ledge, facing into the cliff toward iron
  const robot = makeRobot();
  robot.position.set(ledge[0] + 0.5, ledge[1] - 0.5, CUT - 0.6);
  robot.rotation.y = Math.PI * 0.75;
  scene.add(robot);
  const visorLight = new THREE.PointLight(0x5fd1a5, 6, 6, 2);
  visorLight.position.set(0, 1.5, 0.6); robot.add(visorLight);

  const diamondLight = new THREE.PointLight(0x56f0e0, 7, 10, 2);
  diamondLight.position.set(featDiamond[0], featDiamond[1], CUT + 1.5);
  scene.add(diamondLight);
  const furnaceLight = new THREE.PointLight(0xff8a2a, 0, 7, 2);
  furnaceLight.position.set(furnacePos[0], furnacePos[1], furnacePos[2] + 1.2);
  scene.add(furnaceLight);

  // Fireflies
  const ffCount = isSmall ? 60 : 160;
  const ffGeo = new THREE.BufferGeometry();
  const ffPos = new Float32Array(ffCount * 3), ffSeed = new Float32Array(ffCount);
  for (let i = 0; i < ffCount; i++) {
    ffPos[i * 3] = (rand() - 0.5) * 34; ffPos[i * 3 + 1] = rand() * 9 - 1; ffPos[i * 3 + 2] = (rand() - 0.5) * 30;
    ffSeed[i] = rand() * 100;
  }
  ffGeo.setAttribute("position", new THREE.BufferAttribute(ffPos, 3));
  ffGeo.setAttribute("seed", new THREE.BufferAttribute(ffSeed, 1));
  const ffMat = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: { time: { value: 0 }, scale: { value: renderer.getPixelRatio() } },
    vertexShader: `attribute float seed; uniform float time; uniform float scale; varying float vA;
      void main(){ vec3 p = position; p.x += sin(time*0.4+seed)*0.8; p.y += sin(time*0.6+seed*1.7)*0.5; p.z += cos(time*0.35+seed)*0.8;
        vec4 mv = modelViewMatrix * vec4(p,1.0); gl_Position = projectionMatrix * mv;
        vA = 0.45 + 0.55*sin(time*2.0+seed*3.0); gl_PointSize = (9.0 * scale) / -mv.z * 10.0; }`,
    fragmentShader: `varying float vA; void main(){ float d = length(gl_PointCoord-0.5); float a = smoothstep(0.5,0.0,d)*vA; gl_FragColor = vec4(1.0,0.82,0.45,a); }`,
  });
  scene.add(new THREE.Points(ffGeo, ffMat));

  // Mining particles near the robot
  const chips = [];
  const chipMat = new THREE.MeshStandardMaterial({ color: 0x8a8d90, roughness: 1 });
  for (let i = 0; i < 10; i++) {
    const c = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.12, 0.12), chipMat);
    c.visible = false; scene.add(c); chips.push({ mesh: c, life: 0, v: new THREE.Vector3() });
  }

  // Build site on the island top: a small hut assembled block by block
  const houseOrigin = [-4, 0, -1];
  houseOrigin[1] = topAt(blocks, -4, -1) + 1;
  const plan = [];
  const W = 5, D = 5;
  for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) plan.push([x, 0, z, "cobble"]);
  for (let y = 1; y <= 3; y++) for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) {
    const edgeX = x === 0 || x === W - 1, edgeZ = z === 0 || z === D - 1;
    if (!edgeX && !edgeZ) continue;
    const corner = edgeX && edgeZ;
    const door = z === D - 1 && x === 2 && y <= 2;
    const window = y === 2 && ((x === 0 || x === W - 1) && z === 2);
    if (door || window) continue;
    plan.push([x, y, z, corner ? "log" : "planks"]);
  }
  for (let x = -1; x <= W; x++) for (let z = -1; z <= D; z++) plan.push([x, 4, z, "planks"]);
  for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) if (x > 0 && x < W - 1 && z > 0 && z < D - 1) plan.push([x, 5, z, "planks"]);
  plan.push([1, 1, 1, "chest"]);
  const houseGeo = new THREE.BoxGeometry(1, 1, 1);
  const chestMat = new THREE.MeshStandardMaterial({ color: 0xa8712f, roughness: 0.8 });
  const torchMat = new THREE.MeshStandardMaterial({ color: 0xffc14d, emissive: 0xffa53d, emissiveIntensity: 2 });
  const houseBlocks = plan.map(([x, y, z, type]) => {
    const m = new THREE.Mesh(type === "chest" ? new THREE.BoxGeometry(0.88, 0.8, 0.88) : houseGeo, type === "chest" ? chestMat : MAT[type]);
    m.position.set(houseOrigin[0] + x, houseOrigin[1] + y, houseOrigin[2] + z);
    m.userData.base = m.position.y;
    m.castShadow = shadows; m.receiveShadow = shadows;
    m.visible = false;
    scene.add(m);
    return m;
  });
  const torch = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.6, 0.16), torchMat);
  torch.position.set(houseOrigin[0] + 3.4, houseOrigin[1] + 1.6, houseOrigin[2] + D + 0.1);
  torch.visible = false; scene.add(torch);
  const torchLight = new THREE.PointLight(0xffa53d, 0, 8, 2);
  torchLight.position.copy(torch.position); scene.add(torchLight);

  // Builder robot for the build scene
  const builder = makeRobot();
  builder.scale.setScalar(1);
  builder.position.set(houseOrigin[0] + 2.5, houseOrigin[1] - 0.5, houseOrigin[2] + D + 2.2);
  builder.rotation.y = Math.PI;
  builder.visible = false;
  scene.add(builder);
  const arrow = new THREE.Group();
  const shaft = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.06, 1.1), new THREE.MeshStandardMaterial({ color: 0x8a6136 }));
  const head = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.16, 0.2), new THREE.MeshStandardMaterial({ color: 0xcfd3d6, metalness: 0.6, roughness: 0.3 }));
  head.position.z = 0.6; arrow.add(shaft, head); arrow.visible = false; scene.add(arrow);

  // Post-processing
  let composer = null;
  if (!isSmall) {
    composer = new EffectComposer(renderer);
    composer.addPass(new RenderPass(scene, camera));
    composer.addPass(new UnrealBloomPass(new THREE.Vector2(window.innerWidth, window.innerHeight), 0.5, 0.55, 0.9));
    composer.addPass(new OutputPass());
  }

  // Camera shots
  const V = (x, y, z) => new THREE.Vector3(x, y, z);
  const cliffZ = CUT + 0.5;
  const treeTop = topAt(blocks, -6, -4);
  const shots = {
    hero: { pos: V(14, 6, 42), target: V(-11, -3, 0) },
    chain: [
      { pos: V(3, treeTop + 1, 11), target: V(-6, treeTop - 1.5, -4) },                      // tree
      { pos: V(tablePos[0] + 6, tablePos[1] + 4, tablePos[2] + 8), target: V(tablePos[0], tablePos[1], tablePos[2]) }, // table
      { pos: V(ledge[0] + 4.5, ledge[1] + 2.5, cliffZ + 7), target: V(ledge[0] + 0.5, ledge[1] + 1, cliffZ - 1) }, // robot pickaxe
      { pos: V(1, -3, cliffZ + 20), target: V(1, -5, cliffZ) },                              // stone face
      { pos: V(featIron[0] + 5, featIron[1] + 3, cliffZ + 11), target: V(featIron[0], featIron[1], cliffZ) }, // iron
      { pos: V(furnacePos[0] + 6, furnacePos[1] + 3.5, furnacePos[2] + 8), target: V(furnacePos[0], furnacePos[1], furnacePos[2]) }, // furnace
      { pos: V(ledge[0] - 3.5, ledge[1] + 2.5, cliffZ + 7), target: V(ledge[0] + 0.5, ledge[1] + 1, cliffZ - 1) }, // robot iron pick
      { pos: V(featDiamond[0] + 3, featDiamond[1] + 1.5, cliffZ + 7), target: V(featDiamond[0], featDiamond[1] + 0.5, cliffZ) }, // diamond
    ],
    build: { pos: V(houseOrigin[0] + 11, houseOrigin[1] + 7, houseOrigin[2] + 15), target: V(houseOrigin[0] + 2.5, houseOrigin[1] + 2, houseOrigin[2] + 2.5) },
    finale: { pos: V(30, 18, 58), target: V(-21, 8, 0) },
  };
  if (isSmall) {
    shots.hero = { pos: V(10, 8, 62), target: V(1, -17, 0) };
    shots.finale = { pos: V(10, 34, 70), target: V(0, 4, 0) };
  }

  return {
    renderer, scene, camera, composer, shots, water, ffMat, stars, robot, builder, arrow, chips,
    houseBlocks, torch, torchLight, furnaceLight, furnaceMat: MAT.furnace[4], diamondMat: MAT.diamond, diamondLight,
    ledge, cliffZ,
  };
}

/* ------------------------------------------------------------------ */
/* Story state driven by scroll                                        */
/* ------------------------------------------------------------------ */
const story = {
  scene: "hero",
  heroP: 0,
  chainP: 0,
  buildP: 0,
  finaleP: 0,
  step: -1,
  pointer: { x: 0, y: 0 },
};

function shotFor(world, t) {
  const s = world.shots;
  const pos = new THREE.Vector3(), target = new THREE.Vector3();
  if (story.scene === "chain") {
    const f = story.chainP * s.chain.length;
    const i = Math.min(s.chain.length - 1, Math.floor(f));
    const j = Math.min(s.chain.length - 1, i + 1);
    // Hold on each subject, then glide to the next during the last 35% of its slot.
    const k = smooth(clamp01((f - i - 0.65) / 0.35));
    pos.lerpVectors(s.chain[i].pos, s.chain[j].pos, k);
    target.lerpVectors(s.chain[i].target, s.chain[j].target, k);
  } else if (story.scene === "build") {
    const a = (story.buildP - 0.5) * 0.9;
    const base = s.build;
    const off = base.pos.clone().sub(base.target);
    off.applyAxisAngle(new THREE.Vector3(0, 1, 0), a);
    pos.copy(base.target).add(off);
    target.copy(base.target);
  } else if (story.scene === "finale") {
    pos.lerpVectors(s.build.pos, s.finale.pos, smooth(story.finaleP));
    target.lerpVectors(s.build.target, s.finale.target, smooth(story.finaleP));
  } else {
    // Hero: slow orbit plus a gentle dolly as the page starts to scroll.
    const orbit = reduceMotion ? 0 : Math.sin(t * 0.06) * 0.08;
    const off = s.hero.pos.clone().sub(s.hero.target);
    off.applyAxisAngle(new THREE.Vector3(0, 1, 0), orbit);
    off.multiplyScalar(1 - story.heroP * 0.25);
    pos.copy(s.hero.target).add(off);
    target.copy(s.hero.target);
  }
  // Pointer parallax
  if (!reduceMotion && !isSmall) {
    pos.x += story.pointer.x * 1.2;
    pos.y += story.pointer.y * 0.6;
  }
  return { pos, target };
}

function startWorld() {
  const canvas = document.getElementById("world");
  let world;
  try {
    world = initWorld(canvas);
  } catch (e) {
    console.warn("WebGL unavailable, keeping the poster image.", e);
    return null;
  }
  const { renderer, camera, composer } = world;
  const camTarget = new THREE.Vector3();
  const first = shotFor(world, 0);
  camera.position.copy(first.pos); camTarget.copy(first.target); camera.lookAt(camTarget);

  let visible = true;
  const scenes = document.querySelectorAll("[data-scene]");
  const visibleSet = new Set();
  const io = new IntersectionObserver((entries) => {
    for (const e of entries) e.isIntersecting ? visibleSet.add(e.target) : visibleSet.delete(e.target);
    visible = visibleSet.size > 0;
  }, { rootMargin: "10% 0px" });
  scenes.forEach((s) => io.observe(s));

  const clock = new THREE.Clock();
  let chipTimer = 0;
  function frame() {
    requestAnimationFrame(frame);
    const dt = Math.min(clock.getDelta(), 0.05);
    const t = clock.elapsedTime;
    if (!visible) return;

    const wantShift = !isSmall && (story.scene === "chain" || story.scene === "build") ? 0.17 : 0;
    world.viewShift = lerp(world.viewShift ?? 0, wantShift, reduceMotion ? 1 : 0.06);
    const w = window.innerWidth, h = window.innerHeight;
    if (Math.abs(world.viewShift) > 0.001) camera.setViewOffset(w, h, -w * world.viewShift, 0, w, h);
    else camera.clearViewOffset();

    const want = shotFor(world, t);
    const damp = reduceMotion ? 1 : 1 - Math.pow(0.0015, dt);
    camera.position.lerp(want.pos, damp);
    camTarget.lerp(want.target, damp);
    camera.lookAt(camTarget);

    world.water.material.uniforms.time.value = t;
    world.water.material.uniforms.camPos.value.copy(camera.position);
    world.ffMat.uniforms.time.value = t;
    world.stars.material.opacity = 0.55 + Math.sin(t * 0.7) * 0.15;

    // Robot mining swing; tool tint follows the current chain step
    const swing = reduceMotion ? 0.4 : (Math.sin(t * 7) * 0.5 + 0.5);
    world.robot.userData.armPivot.rotation.x = -0.4 - swing * 1.1;
    const toolColor = story.step >= 6 ? 0xe4e8ea : story.step >= 3 ? 0x8d9196 : story.step >= 2 ? 0x9b7444 : 0x8fe9ff;
    world.robot.userData.pickMat.color.setHex(toolColor);
    world.robot.userData.pickMat.emissiveIntensity = story.step >= 6 || story.step < 2 ? 0.4 : 0.05;

    chipTimer += dt;
    if (!reduceMotion && chipTimer > 0.14 && swing > 0.95) {
      chipTimer = 0;
      const c = world.chips.find((c) => c.life <= 0);
      if (c) {
        c.life = 0.8; c.mesh.visible = true;
        c.mesh.position.set(world.ledge[0] + 0.1, world.ledge[1] + 0.6, world.cliffZ - 1.3);
        c.v.set((Math.random() - 0.5) * 2.5, 2 + Math.random() * 1.5, 1 + Math.random() * 1.5);
      }
    }
    for (const c of world.chips) {
      if (c.life <= 0) continue;
      c.life -= dt; c.v.y -= 9 * dt;
      c.mesh.position.addScaledVector(c.v, dt);
      c.mesh.rotation.x += dt * 6;
      if (c.life <= 0) c.mesh.visible = false;
    }

    // Furnace lights up during the smelting step
    const lit = story.scene === "chain" && story.step === 5 ? 1 : 0;
    world.furnaceMat.emissiveIntensity = lerp(world.furnaceMat.emissiveIntensity, lit * 3, 0.08);
    world.furnaceLight.intensity = lerp(world.furnaceLight.intensity, lit * 10, 0.08);
    // Diamonds pulse harder when they are the goal
    const goal = story.scene === "chain" && story.step === 7 ? 1 : 0;
    world.diamondMat.emissiveIntensity = 0.9 + goal * (0.5 + Math.sin(t * 4) * 0.25);
    world.diamondLight.intensity = 6 + goal * 5;

    // Build progress
    const inBuild = story.scene === "build" || story.scene === "finale";
    const bp = story.scene === "finale" ? 1 : story.buildP;
    const placeP = clamp01((bp - 0.06) / 0.78);
    const n = world.houseBlocks.length;
    const placed = Math.floor(placeP * n);
    world.houseBlocks.forEach((m, i) => {
      if (!inBuild) { m.visible = false; return; }
      const local = clamp01(placeP * n - i);
      m.visible = i < placed + 1 && local > 0;
      m.position.y = m.userData.base + (1 - smooth(local)) * 2.2;
      m.scale.setScalar(0.6 + smooth(local) * 0.4);
    });
    world.torch.visible = inBuild && placeP > 0.98;
    world.torchLight.intensity = world.torch.visible ? 6 : 0;
    world.builder.visible = inBuild;
    world.robot.visible = !inBuild || story.scene === "finale";
    world.builder.userData.armPivot.rotation.x = -0.4 - swing * 1.1;

    // Arrow dodge beat
    const dodge = clamp01((bp - 0.44) / 0.14);
    const baseX = world.builder.userData.baseX ??= world.builder.position.x;
    const step = Math.sin(Math.min(dodge, 1) * Math.PI);
    world.builder.position.x = baseX + step * 1.6;
    world.arrow.visible = inBuild && dodge > 0 && dodge < 1;
    if (world.arrow.visible) {
      const a = dodge;
      const from = new THREE.Vector3(baseX + 9, world.builder.position.y + 1.4, world.builder.position.z + 9);
      const to = new THREE.Vector3(baseX - 4, world.builder.position.y + 0.9, world.builder.position.z - 4);
      world.arrow.position.lerpVectors(from, to, a);
      world.arrow.lookAt(to);
    }
    const buildEl = document.getElementById("build");
    buildEl?.classList.toggle("dodge", bp > 0.44);
    buildEl?.classList.toggle("show-inset", story.scene === "build" && bp > 0.25);
    const counter = document.querySelector(".build-count");
    if (counter) counter.textContent = String(Math.min(n, placed));

    if (composer) composer.render(); else renderer.render(world.scene, camera);
  }
  frame();

  window.addEventListener("resize", () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
    composer?.setSize(window.innerWidth, window.innerHeight);
  });
  if (!reduceMotion && !isSmall) {
    window.addEventListener("pointermove", (e) => {
      story.pointer.x = (e.clientX / window.innerWidth - 0.5) * 2;
      story.pointer.y = -(e.clientY / window.innerHeight - 0.5) * 2;
    }, { passive: true });
  }
  const total = document.querySelector(".build-total");
  if (total) total.textContent = String(world.houseBlocks.length);
  document.body.classList.add("webgl-ready");
  return world;
}

/* ------------------------------------------------------------------ */
/* Page choreography                                                   */
/* ------------------------------------------------------------------ */
function typeInto(el, text, speed = 70) {
  if (el.dataset.typed) return;
  el.dataset.typed = "1";
  if (reduceMotion) { el.textContent = text; return; }
  let i = 0;
  const id = setInterval(() => { el.textContent = text.slice(0, ++i); if (i >= text.length) clearInterval(id); }, speed);
}

function setStep(step) {
  if (step === story.step) return;
  story.step = step;
  document.querySelectorAll(".steps li").forEach((li) => {
    const s = Number(li.dataset.step);
    li.classList.toggle("active", s === step);
    li.classList.toggle("done", s < step);
  });
}

function setupInventory(icons) {
  const grid = document.getElementById("inv-grid");
  const hotbar = document.getElementById("inv-hotbar");
  const slots = [];
  for (let i = 0; i < 27; i++) { const d = document.createElement("div"); d.className = "slot"; grid.appendChild(d); slots.push(d); }
  for (let i = 0; i < 9; i++) { const d = document.createElement("div"); d.className = "slot"; hotbar.appendChild(d); slots.push(d); }
  // Gathered ingredients first, then the requested outputs
  const fill = [
    [27, "log", 5], [28, "planks", 12], [29, "table", 1], [30, "cobble", 9], [31, "coal", 2],
    [0, "stick", 4], [1, "chest", 2], [2, "torch", 8],
  ];
  const label = { log: "Oak log", planks: "Planks", table: "Crafting table", cobble: "Cobblestone", coal: "Coal", stick: "Stick", chest: "Chest", torch: "Torch" };
  fill.forEach(([idx, item, count]) => {
    const s = slots[idx];
    const img = document.createElement("img"); img.src = icons[item]; img.alt = label[item]; s.appendChild(img);
    const c = document.createElement("span"); c.className = "count"; c.textContent = count > 1 ? count : ""; s.appendChild(c);
  });
  const order = fill.map(([idx]) => slots[idx]);
  if (reduceMotion || !ScrollTrigger) { order.forEach((s) => s.classList.add("filled")); return; }
  ScrollTrigger.create({
    trigger: "#lists", start: "top 65%", end: "bottom 70%", scrub: true,
    onUpdate: (st) => {
      const k = Math.floor(st.progress * (order.length + 0.999));
      order.forEach((s, i) => s.classList.toggle("filled", i < k));
    },
  });
}

function setupScroll() {
  if (!ScrollTrigger) return;
  ScrollTrigger.create({ start: 40, end: "max", onToggle: (st) => document.querySelector(".nav").classList.toggle("scrolled", st.isActive) });

  ScrollTrigger.create({ trigger: "#hero", start: "top top", end: "bottom top", onUpdate: (st) => { story.heroP = st.progress; },
    onEnter: () => { story.scene = "hero"; }, onEnterBack: () => { story.scene = "hero"; } });

  ScrollTrigger.create({
    trigger: "#chain", start: "top top", end: "bottom bottom",
    onEnter: () => { story.scene = "chain"; typeInto(document.querySelector(".chat-text"), "@get diamond"); },
    onEnterBack: () => { story.scene = "chain"; },
    onLeaveBack: () => { story.scene = "hero"; setStep(-1); },
    onUpdate: (st) => {
      story.chainP = reduceMotion ? 0 : st.progress;
      setStep(Math.min(7, Math.floor(st.progress * 8)));
    },
  });

  // The build world takes over as soon as its section enters the viewport, so the lists
  // section never shows the previous chain shot through its fading background.
  ScrollTrigger.create({
    trigger: "#build", start: "top bottom", end: "bottom bottom",
    onEnter: () => { story.scene = "build"; }, onEnterBack: () => { story.scene = "build"; },
    onLeaveBack: () => { story.scene = "chain"; },
  });
  ScrollTrigger.create({
    trigger: "#build", start: "top top", end: "bottom bottom",
    onUpdate: (st) => { story.buildP = st.progress; },
  });
  ScrollTrigger.create({
    trigger: "#download", start: "top bottom", end: "bottom bottom",
    onEnter: () => { story.scene = "finale"; }, onLeaveBack: () => { story.scene = "build"; story.buildP = 1; },
    onUpdate: (st) => { story.finaleP = st.progress; },
  });

  // Footage: the frame grows from a card to full bleed, then the caption arrives.
  const frame = document.querySelector(".footage-frame");
  const caption = document.querySelector(".footage-caption");
  if (!reduceMotion) {
    gsap.fromTo(frame, { scale: 0.62, rotateX: 8, borderRadius: 18 }, {
      // Fill the viewport on wide screens; on phones stop at full width so the footage stays readable.
      scale: () => isSmall ? window.innerWidth / frame.offsetWidth
        : Math.max(window.innerWidth / frame.offsetWidth, window.innerHeight / frame.offsetHeight),
      rotateX: 0, borderRadius: 0, ease: "none",
      scrollTrigger: { trigger: "#footage", start: "top top", end: "55% bottom", scrub: true, invalidateOnRefresh: true },
    });
    gsap.fromTo(caption, { opacity: 0, y: 30 }, { opacity: 1, y: 0, ease: "none",
      scrollTrigger: { trigger: "#footage", start: "45% bottom", end: "70% bottom", scrub: true } });
  }

  // Panel: three tabs advance with scroll, and stay clickable.
  const tabs = [...document.querySelectorAll(".panel-tabs button")];
  const shots = [...document.querySelectorAll(".shot")];
  const descs = [...document.querySelectorAll(".panel-desc")];
  let panelTrigger;
  const showTab = (i) => {
    tabs.forEach((b, j) => b.setAttribute("aria-selected", String(i === j)));
    shots.forEach((s, j) => s.classList.toggle("on", i === j));
    descs.forEach((d, j) => { d.hidden = i !== j; });
  };
  showTab(0);
  if (!reduceMotion) {
    panelTrigger = ScrollTrigger.create({ trigger: "#panel", start: "top top", end: "bottom bottom",
      onUpdate: (st) => showTab(Math.min(2, Math.floor(st.progress * 3))) });
  }
  tabs.forEach((b, i) => b.addEventListener("click", () => {
    if (panelTrigger) {
      const y = panelTrigger.start + (panelTrigger.end - panelTrigger.start) * ((i + 0.5) / 3);
      window.scrollTo({ top: y, behavior: "smooth" });
    } else showTab(i);
  }));

  // Count-up for the test total
  const num = document.querySelector(".big-num");
  ScrollTrigger.create({ trigger: num, start: "top 85%", once: true, onEnter: () => {
    const target = Number(num.dataset.count);
    if (reduceMotion) { num.textContent = target.toLocaleString("en-US"); return; }
    const o = { v: 0 };
    gsap.to(o, { v: target, duration: 1.8, ease: "power3.out", onUpdate: () => { num.textContent = Math.round(o.v).toLocaleString("en-US"); } });
  } });

  // Reveal headings in plain sections
  if (!reduceMotion) {
    gsap.utils.toArray(".proof > h2, .credits > h2, .lists-copy, .cell, .credit, .download-inner > *").forEach((el) => {
      gsap.from(el, { opacity: 0, y: 34, duration: 1, ease: "power3.out", scrollTrigger: { trigger: el, start: "top 88%" } });
    });
  }
}

function setupInsetVideo() {
  // Animated WebP instead of <video>: Chrome left a stale compositor layer for a video in this pinned section.
  const img = document.querySelector(".inset-video");
  if (!img || reduceMotion) return;
  const io = new IntersectionObserver(([e]) => {
    if (e.isIntersecting) { img.src = img.dataset.anim; io.disconnect(); }
  }, { rootMargin: "200px" });
  io.observe(img);
}

function setupVideo() {
  const video = document.getElementById("footage-video");
  const btn = document.querySelector(".play-toggle");
  if (!video) return;
  let userPaused = reduceMotion;
  const setIcon = () => {
    btn.innerHTML = video.paused ? '<i class="ph-bold ph-play"></i>' : '<i class="ph-bold ph-pause"></i>';
    btn.setAttribute("aria-label", video.paused ? "Play footage" : "Pause footage");
  };
  new IntersectionObserver(([e]) => {
    if (e.isIntersecting && !userPaused) video.play().catch(() => {});
    else video.pause();
  }, { threshold: 0.25 }).observe(video);
  btn.addEventListener("click", () => {
    if (video.paused) { userPaused = false; video.play(); } else { userPaused = true; video.pause(); }
  });
  video.addEventListener("play", setIcon);
  video.addEventListener("pause", setIcon);
  setIcon();
}

/* ------------------------------------------------------------------ */
document.fonts?.ready.finally(() => document.body.classList.add("loaded"));
setTimeout(() => document.body.classList.add("loaded"), 1200);
let icons = {};
try { icons = renderItemIcons(); } catch (e) { console.warn("Item icons unavailable", e); }
setupInventory(icons);
startWorld();
setupScroll();
setupVideo();
setupInsetVideo();
if (reduceMotion) setStep(7);
