package com.skycop.sim.render

import com.skycop.sim.core.City
import com.skycop.sim.core.Mat
import com.skycop.sim.core.Signals
import com.skycop.sim.core.Tex

internal object Shaders {
    private val TILE_SIZES = Tex.values().joinToString(", ") { "vec2(${it.width}, ${it.height})" }

    private const val HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

    private const val SKY_COLOR = """
// Set every frame from Environment: the light, its color, the sky, haze, night, wetness and rain.
uniform vec4 uSun;      // xyz toward the sun or moon, w how dark the night is
uniform vec4 uSunColor; // rgb light color, w how wet the streets are
uniform vec4 uSkyTop;   // rgb sky overhead, w haze density
uniform vec4 uSkyLow;   // rgb sky at the horizon, w how hard it rains
#define SUN uSun.xyz
// Dhaka's sky: a dusty haze at the horizon fading to the sky overhead, with a glow around the sun.
vec3 skyColor(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = mix(uSkyLow.rgb, uSkyTop.rgb, pow(h, 0.62));
    float glow = max(dot(d, SUN), 0.0);
    float halo = glow * glow; halo *= halo; halo *= halo;
    return c + uSunColor.rgb * (halo * 0.08 + pow(glow, 40.0) * 0.06) * (1.0 - uSun.w);
}
// Filmic curve (ACES fit) from linear light to the screen, with a slight warm grade.
vec3 film(vec3 x) {
    x *= vec3(0.56, 0.54, 0.51);
    x = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
    x = clamp(mix(vec3(dot(x, vec3(0.3, 0.55, 0.15))), x, 1.12), 0.0, 1.0);
    return sqrt(x);
}
float hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}
"""

    private const val INSTANCE_TRANSFORM = """
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec4 aPlace;
layout(location = 4) in vec4 aTint;
uniform mat4 uViewProjection;
uniform mat4 uModel;
vec3 turn(vec3 p) {
    float c = cos(aPlace.w); float s = sin(aPlace.w);
    return vec3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
}
"""

    val WORLD_VERTEX = HEADER + INSTANCE_TRANSFORM + """
uniform mat4 uLight;
uniform vec3 uEye;
const vec2 TILE[${Tex.values().size}] = vec2[]($TILE_SIZES);
out vec3 vWorld;
out vec3 vFromEye;
out vec4 vShadow;
out vec3 vNormal;
out vec2 vUv;
flat out vec3 vTangent;
flat out vec3 vBitangent;
flat out vec3 vColor;
flat out int vMaterial;
void main() {
    vec3 world = (uModel * vec4(turn(aPosition), 1.0)).xyz + aPlace.xyz;
    mat3 rotation = mat3(uModel);
    vNormal = rotation * turn(aNormal.xyz);

    // The face code chooses which way the texture lies on this triangle.
    int face = int(aNormal.w * 127.0 + 0.5);
    vec3 tangent; vec3 bitangent = vec3(0.0, 1.0, 0.0);
    if (face == 0) tangent = vec3(0.0, 0.0, -1.0);
    else if (face == 1) tangent = vec3(0.0, 0.0, 1.0);
    else if (face == 2) tangent = vec3(1.0, 0.0, 0.0);
    else if (face == 3) tangent = vec3(-1.0, 0.0, 0.0);
    else if (face == 6) tangent = normalize(vec3(aNormal.z, 0.0, -aNormal.x));
    else { tangent = vec3(1.0, 0.0, 0.0); bitangent = vec3(0.0, 0.0, face == 4 ? -1.0 : 1.0); }

    int material = int(aColor.a * 255.0 + 0.5);
    if (material == ${Mat.LEDGE}) material = ${Mat.TEXTURED + Tex.CONCRETE.ordinal};
    vUv = vec2(0.0);
    if (material >= ${Mat.TEXTURED}) {
        vec2 at = vec2(dot(aPosition, tangent), dot(aPosition, bitangent));
        // Floors are counted from the top of the curb.
        if (face < 4 || face == 6) at.y -= ${City.CURB};
        vUv = at / TILE[material - ${Mat.TEXTURED}];
    }
    vTangent = rotation * turn(tangent);
    vBitangent = rotation * turn(bitangent);
    vColor = material == ${Mat.TINT} ? aColor.rgb * aTint.rgb : aColor.rgb;
    vMaterial = material;
    vWorld = world;
    vFromEye = world - uEye;
    vShadow = uLight * vec4(world + vNormal * 0.09, 1.0);
    gl_Position = uViewProjection * vec4(world, 1.0);
}
"""

    val WORLD_FRAGMENT = HEADER + SKY_COLOR + """
precision highp sampler2DShadow;
precision mediump sampler2DArray;
uniform sampler2DShadow uShadowMap;
uniform sampler2DArray uAlbedo;
uniform sampler2DArray uRelief;
uniform float uShadowOn;
uniform float uShadowTexel;
uniform float uReliefOn;
uniform float uTime;
uniform float uSignalTime;
in vec3 vWorld;
in vec3 vFromEye;
in vec4 vShadow;
in vec3 vNormal;
in vec2 vUv;
flat in vec3 vTangent;
flat in vec3 vBitangent;
flat in vec3 vColor;
flat in int vMaterial;
out vec4 fragColor;

// Anti-aliased coverage of a stripe, fading out instead of sparkling once thinner than a pixel.
float band(float distanceFromCenter, float halfWidth, float px) {
    return clamp((halfWidth - abs(distanceFromCenter)) / px + 0.5, 0.0, 1.0) * min(1.0, 2.0 * halfWidth / px);
}

// Bare ground between the mapped roads and buildings: Dhaka's packed dusty earth, brick dust and
// worn patches of grass.
vec3 groundColor(vec3 p, vec2 gradX, vec2 gradY) {
    float g = ${Tex.GRASS.width};
    vec3 grass = textureGrad(uAlbedo, vec3(p.xz / g, ${Tex.GRASS.ordinal}.0), gradX / g, gradY / g).rgb;
    float d = ${Tex.CONCRETE.width};
    vec3 dirt = textureGrad(uAlbedo, vec3(p.xz / d, ${Tex.CONCRETE.ordinal}.0), gradX / d, gradY / d).rgb * vec3(0.78, 0.72, 0.63);
    // The same textures at a much larger scale break up the repeat and make the patches.
    float large = 37.0;
    float broad = textureGrad(uAlbedo, vec3(p.xz / large, ${Tex.GRASS.ordinal}.0), gradX / large, gradY / large).g;
    float patches = smoothstep(0.3, 0.58, broad);
    // Mostly overgrown with weeds and grass, as empty plots in Dhaka are, with bare dusty tracks.
    vec3 weeds = mix(grass, vec3(0.42, 0.47, 0.3), 0.35) * 0.8;
    vec3 c = mix(dirt, weeds, patches * 0.85) * (0.78 + broad * 0.36);
    // Brick dust and dark damp ground.
    float stain = noise(p.xz * 0.09);
    c = mix(c, vec3(0.55, 0.40, 0.32), smoothstep(0.62, 0.9, stain) * 0.35);
    return c * mix(1.0, 0.72, smoothstep(0.55, 0.15, stain));
}

// The sky above the horizon, a suggestion of the city below it.
vec3 surroundings(vec3 d) {
    return mix(vec3(0.24, 0.26, 0.27) * (1.0 - 0.85 * uSun.w), skyColor(d), smoothstep(-0.12, 0.06, d.y));
}

void main() {
    int m = vMaterial;
    bool textured = m >= ${Mat.TEXTURED};
    int layerId = max(m - ${Mat.TEXTURED}, 0);
    float layer = float(layerId);
    // Derivatives and implicit-LOD lookups happen before any branch on the material.
    vec4 albedo = texture(uAlbedo, vec3(vUv, layer));
    vec2 relief = texture(uRelief, vec3(vUv, layer)).rg * 2.0 - 1.0;
    vec2 gradX = dFdx(vWorld.xz);
    vec2 gradY = dFdy(vWorld.xz);

    vec3 n = normalize(vNormal);
    vec3 c = vColor;
    float gloss = 0.0;
    float occlusion = 1.0;
    bool emissive = false;
    // Light of its own, added after the scene is exposed: rooms lit at night.
    vec3 glow = vec3(0.0);
    if (textured) {
        gloss = albedo.a;
        c = albedo.rgb * mix(vColor, vec3(1.0), smoothstep(0.4, 0.7, gloss));
        n = normalize(n + (vTangent * relief.x + vBitangent * relief.y) * uReliefOn);
        // Foliage: a deeper, less saturated green than the painted leaves.
        if (layerId == ${Tex.LEAVES.ordinal}) c *= vec3(0.74, 0.8, 0.68);
        if (layerId == ${Tex.GRASS.ordinal}) {
            // Lawns are never one even green: worn, dry and lush patches.
            float lush = noise(vWorld.xz * 0.06) * 0.6 + noise(vWorld.xz * 0.25) * 0.4;
            c = mix(c, vec3(0.5, 0.5, 0.33), 0.3 + 0.3 * smoothstep(0.6, 0.2, lush)) * (0.78 + 0.3 * lush);
        }
        bool wall = abs(vBitangent.y) > 0.9;
        bool built = layerId == ${Tex.CONCRETE.ordinal} || (layerId >= ${Tex.BRICK.ordinal} && layerId <= ${Tex.ROOF_TILES.ordinal});
        if (wall) {
            float up = vWorld.y - ${City.CURB};
            // Contact shade where walls meet the pavement, and the street canyon getting less sky low down.
            c *= mix(0.72, 1.0, clamp(vWorld.y / 2.5, 0.0, 1.0));
            occlusion = mix(0.6, 1.0, smoothstep(0.0, 18.0, vWorld.y));
            if (built) {
                float along = dot(vWorld, vTangent);
                float plaster = 1.0 - smoothstep(0.4, 0.7, gloss);
                // The sky hidden under each floor's sunshade.
                float underShade = mod(2.72 - up, 3.3);
                occlusion *= 1.0 - (1.0 - smoothstep(0.0, 0.6, underShade)) * 0.4 * step(3.0, up);
                c *= 1.0 - (1.0 - smoothstep(0.0, 0.25, underShade)) * 0.2 * step(3.0, up) * plaster;
                if (uReliefOn > 0.5) {
                    // Monsoon weathering: dark rain streaks, damp blotches and splash dirt near the ground.
                    float streaks = noise(vec2(along * 1.7, vWorld.y * 0.06));
                    float blotch = noise(vec2(along * 0.13, up * 0.11) + 19.0);
                    float dirt = smoothstep(0.5, 1.0, streaks) * 0.32 * smoothstep(0.3, 0.8, blotch + 0.2)
                               + smoothstep(0.55, 0.95, blotch) * 0.1
                               + (1.0 - smoothstep(0.0, 2.6, up)) * 0.2;
                    c = mix(c, c * vec3(0.55, 0.56, 0.5), clamp(dirt, 0.0, 1.0) * plaster);
                }
                // Every window is its own room: some dark, some with lamps on, some with curtains drawn.
                if (gloss > 0.6) {
                    float h = hash(floor(vec2(along / 3.0, up / 3.3)) + floor(vWorld.xz * 0.03) * 7.0);
                    if (h < 0.3) c *= 0.5;
                    else if (h < 0.42) c = mix(c, vec3(0.75, 0.55, 0.32), 0.4);
                    else if (h < 0.54) c = mix(c, h < 0.48 ? vec3(0.72, 0.48, 0.5) : vec3(0.45, 0.6, 0.66), 0.45);
                    gloss *= 0.55 + h * 0.5;
                    if (h > 0.3 && h < 0.62) glow = vec3(1.0, 0.76, 0.42) * (0.45 + h * 0.7) * uSun.w;
                }
            }
        } else if (built && uReliefOn > 0.5) {
            // Roofs: water stains and grime.
            float stain = noise(vWorld.xz * 0.21) * 0.6 + noise(vWorld.xz * 0.9) * 0.4;
            c *= 1.0 - smoothstep(0.45, 0.85, stain) * 0.3;
        } else if (layerId == ${Tex.ASPHALT.ordinal}) {
            // Patched, dusty roads: repaired stretches and drifts of dust.
            float patchwork = noise(vWorld.xz * 0.07);
            float dust = noise(vWorld.xz * 0.21 + 5.0);
            c *= 0.84 + patchwork * 0.3;
            c = mix(c, vec3(0.56, 0.53, 0.47), smoothstep(0.55, 0.95, dust) * 0.4);
        }
    } else if (m == ${Mat.GROUND}) {
        c = groundColor(vWorld, gradX, gradY);
    } else if (m == ${Mat.STRIPED}) {
        // Painted kerb stones, about 0.9 m each, with worn paint and grime.
        float block = mod(floor(vWorld.x * 1.1) + floor(vWorld.z * 1.1), 2.0);
        c = block < 0.5 ? vColor : vec3(0.13, 0.13, 0.12);
        c *= 0.78 + 0.22 * noise(vWorld.xz * 3.7 + vWorld.y * 5.0);
        gloss = 0.08;
    } else if (m == ${Mat.GLOSSY} || m == ${Mat.TINT}) {
        gloss = 0.5;
    } else if (m == ${Mat.GLASS}) {
        gloss = 0.95;
    } else if (m == ${Mat.WATER}) {
        gloss = 1.0;
        n = normalize(vec3(
            0.05 * sin(vWorld.x * 2.0 + uTime * 1.7) + 0.03 * sin(vWorld.z * 5.1 - uTime * 2.2), 1.0,
            0.05 * sin(vWorld.z * 2.3 + uTime * 1.3) + 0.03 * sin(vWorld.x * 4.3 + uTime * 2.6)));
        c *= vec3(0.55, 0.6, 0.5);
    } else if (m == ${Mat.EMISSIVE}) {
        emissive = true;
    } else if (m == ${Mat.LAMP}) {
        // Lamp lenses: pale by day, glowing at night.
        c *= mix(0.72, 1.35, uSun.w); emissive = true;
    } else if (m == ${Mat.STROBE}) {
        float blink = step(0.5, fract(uTime * 2.0 + (vColor.r > 0.5 ? 0.0 : 0.5)));
        c *= 0.3 + blink; emissive = true;
    } else if (m >= ${Mat.SIGNAL}) {
        int id = m - ${Mat.SIGNAL};
        int group = id / 6; int axis = (id % 6) / 3; int light = id % 3;
        float t = mod(uSignalTime + float(group) * 6.5 + float(axis) * 13.0, ${Signals.CYCLE});
        int lit = t < ${Signals.GREEN} ? 0 : (t < ${Signals.YELLOW} ? 1 : 2);
        c *= light == lit ? 1.25 : 0.13; emissive = true;
    }

    // Monsoon: everything facing up darkens and shines when wet, with puddles that ripple in the rain.
    float wetness = uSunColor.w;
    if (wetness > 0.01 && !emissive && m != ${Mat.WATER}) {
        if (vNormal.y > 0.75 && !(textured && (layerId == ${Tex.GRASS.ordinal} || layerId == ${Tex.LEAVES.ordinal}))) {
            float puddle = smoothstep(0.6, 0.74, noise(vWorld.xz * 0.23) * 0.7 + noise(vWorld.xz * 0.9) * 0.3) * wetness;
            c *= mix(1.0, 0.6, wetness);
            gloss = max(gloss, wetness * 0.32 + puddle * 0.6);
            n = normalize(mix(n, vec3(0.0, 1.0, 0.0), puddle));
            float ripple = uSkyLow.w * puddle * 0.12;
            n = normalize(n + vec3(noise(vWorld.xz * 7.0 + uTime * 3.1) - 0.5, 0.0, noise(vWorld.zx * 7.0 - uTime * 2.7) - 0.5) * ripple);
        } else if (textured) {
            c *= 1.0 - 0.16 * wetness;
        }
    }

    float d = length(vFromEye);
    if (!emissive) {
        float facing = max(dot(n, SUN), 0.0);
        float sun = 1.0;
        vec3 s = vShadow.xyz * 0.5 + 0.5;
        if (uShadowOn > 0.5 && facing > 0.0 && s.z < 1.0) {
            vec2 e = abs(s.xy - 0.5) * 2.0;
            float fade = smoothstep(0.85, 1.0, max(e.x, e.y));
            if (fade < 1.0) {
                float texel = uShadowTexel;
                // Four filtered taps on a rotated grid soften the stair-steps along shadow edges.
                float lit = 0.25 * (texture(uShadowMap, s + vec3(texel * 1.6, texel * 0.6, 0.0))
                                  + texture(uShadowMap, s - vec3(texel * 1.6, texel * 0.6, 0.0))
                                  + texture(uShadowMap, s + vec3(-texel * 0.6, texel * 1.6, 0.0))
                                  + texture(uShadowMap, s + vec3(texel * 0.6, -texel * 1.6, 0.0)));
                sun = mix(lit, 1.0, fade);
            }
        }
        // Lighting happens in linear light: sky from above, warm bounce from the dusty ground below.
        vec3 base = c * c;
        vec3 skyLight = mix(uSkyLow.rgb, uSkyTop.rgb, 0.6) * 0.85;
        vec3 ambient = mix(skyLight * vec3(0.45, 0.38, 0.27), skyLight, n.y * 0.5 + 0.5) * occlusion;
        // At night the city's sodium lamps light everything a little, warmest low down.
        ambient += vec3(0.09, 0.065, 0.04) * uSun.w * occlusion * (1.0 - 0.5 * smoothstep(0.0, 40.0, vWorld.y));
        vec3 light = base * (ambient + uSunColor.rgb * facing * sun);
        if (gloss > 0.02) {
            vec3 look = vFromEye / d;
            vec3 bounced = reflect(look, n);
            float edge = 1.0 - max(dot(-look, n), 0.0);
            vec3 mirror = surroundings(bounced);
            light = mix(light, mirror * mirror * 1.6 * occlusion, gloss * (0.14 + 0.62 * edge * edge * edge));
            float glint = max(dot(bounced, SUN), 0.0);
            glint *= glint; glint *= glint; glint *= glint; glint *= glint; glint *= glint;
            light += uSunColor.rgb * glint * gloss * sun;
        }
        // A longer exposure at night, as the eye adapts.
        c = film(light * (1.0 + 1.1 * uSun.w)) + glow;
    }
    // Dhaka's haze swallows the distance.
    float f = d * uSkyTop.w;
    float fog = 1.0 - exp(-f * f);
    if (fog > 0.003) c = mix(c, skyColor(vec3(vFromEye.x, max(vFromEye.y, 0.0) * 0.4, vFromEye.z) / d), fog);
    fragColor = vec4(c, 1.0);
}
"""

    const val SHADOW_VERTEX = HEADER + INSTANCE_TRANSFORM + """
void main() {
    gl_Position = uViewProjection * vec4((uModel * vec4(turn(aPosition), 1.0)).xyz + aPlace.xyz, 1.0);
    // Sunshades stay out of the shadow map; the facade shader shades under them instead.
    if (int(aColor.a * 255.0 + 0.5) == ${Mat.LEDGE}) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
"""

    const val SHADOW_FRAGMENT = HEADER + """
void main() {}
"""

    /** A single triangle that covers the viewport. */
    private const val FULLSCREEN = """
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 1.0, 1.0);
}
"""

    const val SKY_VERTEX = HEADER + FULLSCREEN

    const val SKY_FRAGMENT = HEADER + SKY_COLOR + """
uniform mat4 uBasis;
uniform vec2 uTan;
uniform float uTime;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec3 d = normalize(mat3(uBasis) * vec3((vUv * 2.0 - 1.0) * uTan, -1.0));
    vec3 c = skyColor(d);
    if (d.y > 0.02) {
        vec2 p = d.xz / (d.y + 0.14) * 2.2 + vec2(uTime * 0.008, 0.0);
        float n = noise(p) * 0.57 + noise(p * 2.7) * 0.29 + noise(p * 6.1) * 0.14;
        // Clouds: white by day, lit orange at dusk, dark at night, a gray blanket in the monsoon.
        float rain = uSkyLow.w;
        vec3 cloud = mix(vec3(0.96, 0.97, 0.98), uSkyLow.rgb * 1.05, 0.35 + 0.35 * rain) * (1.0 - 0.85 * uSun.w);
        c = mix(c, cloud, smoothstep(0.52 - 0.3 * rain, 0.78 - 0.2 * rain, n) * smoothstep(0.02, 0.2, d.y) * (0.8 + 0.15 * rain));
        float open = (1.0 - rain) * (1.0 - uSun.w);
        c = mix(c, vec3(1.0, 0.96, 0.84), smoothstep(0.9992, 0.9996, dot(d, SUN)) * open);
        // Stars and the moon on clear nights.
        float star = step(0.9965, hash(floor(d.xz / (d.y + 0.3) * 260.0)));
        c += vec3(0.75, 0.8, 0.9) * star * uSun.w * (1.0 - rain) * smoothstep(0.1, 0.4, d.y);
        c = mix(c, vec3(0.92, 0.93, 0.88), smoothstep(0.9993, 0.9996, dot(d, SUN)) * uSun.w * (1.0 - rain));
    }
    fragColor = vec4(c, 1.0);
}
"""

    /** Soft shadow decals, multiplied over the city, fading out into the haze like everything else. */
    const val SHADE_VERTEX = HEADER + """
layout(location = 0) in vec3 aPosition;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProjection;
uniform vec3 uEye;
uniform float uFog;
out float vShade;
void main() {
    float f = length(aPosition - uEye) * uFog;
    vShade = mix(aColor.r, 1.0, 1.0 - exp(-f * f));
    gl_Position = uViewProjection * vec4(aPosition, 1.0);
}
"""

    const val SHADE_FRAGMENT = HEADER + """
in float vShade;
out vec4 fragColor;
void main() {
    fragColor = vec4(vec3(vShade), 1.0);
}
"""

    /** Lamp light pools: the decal's gray turned into added warm light (0 is the brightest). */
    const val POOL_FRAGMENT = HEADER + """
uniform vec3 uTint;
in float vShade;
out vec4 fragColor;
void main() {
    fragColor = vec4(uTint * (1.0 - vShade), 1.0);
}
"""

    /**
     * Monsoon rain: streaks in a 50 m box that travels with the viewer, each falling and wrapping
     * around, slanted by the wind and stretched along their fall. aCorner.xyz is a streak's place in
     * the box, w which corner of its quad this vertex is.
     */
    const val RAIN_VERTEX = HEADER + """
layout(location = 0) in vec4 aCorner;
uniform mat4 uViewProjection;
uniform vec3 uEye;
uniform vec3 uRight;
uniform float uTime;
out float vAlong;
void main() {
    vec3 box = aCorner.xyz;
    float fall = 9.0 + fract(box.x * 7.13) * 3.0;
    vec3 p = vec3(box.x, box.y - uTime * fall, box.z);
    p = mod(p - uEye + 25.0, 50.0) - 25.0 + uEye;
    vec3 slant = normalize(vec3(0.18, -1.0, 0.08));
    int corner = int(aCorner.w + 0.5);
    float along = (corner == 1 || corner == 2) ? 1.0 : 0.0;
    float side = (corner >= 2) ? 1.0 : -1.0;
    p += slant * along * 0.75 + uRight * side * 0.012;
    vAlong = along;
    gl_Position = uViewProjection * vec4(p, 1.0);
}
"""

    const val RAIN_FRAGMENT = HEADER + """
uniform vec4 uRainColor;
in float vAlong;
out vec4 fragColor;
void main() {
    fragColor = vec4(uRainColor.rgb, uRainColor.a * (0.35 + 0.65 * vAlong));
}
"""

    /** Unlit translucent geometry for the rotor discs. */
    const val GLOW_VERTEX = HEADER + """
layout(location = 0) in vec3 aPosition;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProjection;
uniform mat4 uModel;
out vec3 vColor;
void main() {
    vColor = aColor.rgb;
    gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
}
"""

    const val GLOW_FRAGMENT = HEADER + """
uniform vec4 uGlow;
in vec3 vColor;
out vec4 fragColor;
void main() {
    fragColor = vec4(vColor * uGlow.rgb, uGlow.a);
}
"""

    const val HUD_VERTEX = HEADER + """
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
uniform vec2 uHalfSize;
out vec2 vUv;
out vec4 vColor;
void main() {
    vUv = aUv; vColor = aColor;
    gl_Position = vec4(aPosition.x / uHalfSize.x, -aPosition.y / uHalfSize.y, 0.0, 1.0);
}
"""

    const val HUD_FRAGMENT = HEADER + """
uniform sampler2D uFont;
in vec2 vUv;
in vec4 vColor;
out vec4 fragColor;
void main() {
    fragColor = vec4(vColor.rgb, vColor.a * texture(uFont, vUv).a);
}
"""

    const val PRESENT_VERTEX = HEADER + FULLSCREEN

    /** Upscales the render target, applies lens barrel correction per eye and the comfort vignette. */
    const val PRESENT_FRAGMENT = HEADER + """
uniform sampler2D uImage;
uniform vec4 uRect;
uniform vec2 uTexel;
uniform float uAspect;
uniform vec2 uLens;
uniform float uShift;
uniform float uVignette;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec2 d = vUv * 2.0 - 1.0;
    d.x -= uShift;
    vec2 scale = uAspect > 1.0 ? vec2(uAspect, 1.0) : vec2(1.0, 1.0 / uAspect);
    vec2 q = d * scale;
    float r2 = dot(q, q);
    vec2 source = d * (1.0 + uLens.x * r2 + uLens.y * r2 * r2) / (1.0 + uLens.x + uLens.y);
    vec2 edge = abs(source);
    if (edge.x > 1.0 || edge.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 uv = clamp(uRect.xy + (source * 0.5 + 0.5) * uRect.zw, uRect.xy + uTexel, uRect.xy + uRect.zw - uTexel);
    vec3 c = texture(uImage, uv).rgb;
    c *= 1.0 - uVignette * smoothstep(0.55, 1.05, sqrt(r2));
    fragColor = vec4(c, 1.0);
}
"""
}
