package com.skycop.sim.render

import com.skycop.sim.core.City
import com.skycop.sim.core.Mat
import com.skycop.sim.core.Signals
import com.skycop.sim.core.Tex

internal object Shaders {
    private val TILE_SIZES = Tex.values().joinToString(", ") { "vec2(${it.width}, ${it.height})" }

    private const val HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

    private const val SKY_COLOR = """
const vec3 SUN = normalize(vec3(0.45, 0.78, 0.38));
vec3 skyColor(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = mix(vec3(0.74, 0.82, 0.88), vec3(0.22, 0.45, 0.74), sqrt(h));
    float glow = max(dot(d, SUN), 0.0);
    glow *= glow; glow *= glow; glow *= glow;
    return c + vec3(1.0, 0.8, 0.55) * glow * 0.22;
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
    else { tangent = vec3(1.0, 0.0, 0.0); bitangent = vec3(0.0, 0.0, face == 4 ? -1.0 : 1.0); }

    int material = int(aColor.a * 255.0 + 0.5);
    vUv = vec2(0.0);
    if (material >= ${Mat.TEXTURED}) {
        vec2 at = vec2(dot(aPosition, tangent), dot(aPosition, bitangent));
        // Floors are counted from the top of the curb.
        if (face < 4) at.y -= ${City.CURB};
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

vec3 groundColor(vec3 p, vec2 gradX, vec2 gradY) {
    float wx = max(abs(gradX.x) + abs(gradY.x), 0.002);
    float wz = max(abs(gradX.y) + abs(gradY.y), 0.002);
    float dx = p.x - floor(p.x / ${City.PITCH} + 0.5) * ${City.PITCH};
    float dz = p.z - floor(p.z / ${City.PITCH} + 0.5) * ${City.PITCH};
    float limit = ${City.BOUNDS + 18f};
    bool inside = abs(p.x) < limit && abs(p.z) < limit;
    bool alongZ = inside && abs(dx) < ${City.ROAD_HALF} && abs(p.x) < ${City.HALF + City.ROAD_HALF};
    bool alongX = inside && abs(dz) < ${City.ROAD_HALF} && abs(p.z) < ${City.HALF + City.ROAD_HALF};

    float size = alongZ || alongX ? ${Tex.ASPHALT.width} : ${Tex.GRASS.width};
    float layer = alongZ || alongX ? ${Tex.ASPHALT.ordinal}.0 : ${Tex.GRASS.ordinal}.0;
    vec3 close = textureGrad(uAlbedo, vec3(p.xz / size, layer), gradX / size, gradY / size).rgb;
    // The same texture at a much larger scale hides the repeat from the air.
    float large = size * 7.3;
    float broad = textureGrad(uAlbedo, vec3(p.xz / large, layer), gradX / large, gradY / large).g;
    if (!alongZ && !alongX) return close * (0.72 + broad * 0.6);
    vec3 road = close * (0.78 + broad * 0.7);
    if (alongZ && alongX) return road * 0.94;

    float across = alongZ ? dx : dz;
    float along = alongZ ? dz : dx;
    float wa = alongZ ? wx : wz;
    float wl = alongZ ? wz : wx;
    float t = abs(along);
    float side = abs(across);

    // Darker wheel tracks in each lane, and a gutter by the curb.
    float lane = side < 3.83 ? side - 2.05 : side - 5.6;
    road *= 1.0 - 0.13 * max(0.0, 1.0 - abs(abs(lane) - 0.8) * 2.6);
    road *= 1.0 - 0.22 * smoothstep(7.6, 8.0, side);

    vec3 white = vec3(0.86, 0.87, 0.84) * clamp(0.62 + close.g * 1.1, 0.0, 1.0);
    float open = smoothstep(12.0, 12.0 + wl + 0.01, t);
    road = mix(road, vec3(0.86, 0.66, 0.2), max(band(side - 0.16, 0.06, wa), 0.0) * open);
    float dash = band(abs(mod(p.x * float(alongX) + p.z * float(alongZ), 8.0) - 4.0), 1.5, wl);
    road = mix(road, white, band(side - 3.83, 0.07, wa) * dash * open);
    road = mix(road, white, band(side - 7.55, 0.08, wa) * open);
    float stripe = band(mod(across + 0.6, 1.2) - 0.6, 0.3, wa);
    stripe = mix(stripe, 0.5, clamp(wa - 0.4, 0.0, 1.0));
    road = mix(road, white, band(t - 9.7, 1.3, wl) * stripe * step(side, 7.2));
    float approaching = alongZ ? step(0.0, dx * dz) : step(dx * dz, 0.0);
    road = mix(road, white, band(t - 11.7, 0.22, wl) * approaching * step(0.35, side) * step(side, 7.4));
    return road;
}

// The sky above the horizon, a suggestion of the city below it.
vec3 surroundings(vec3 d) {
    return mix(vec3(0.24, 0.28, 0.3), skyColor(d), smoothstep(-0.12, 0.06, d.y));
}

void main() {
    int m = vMaterial;
    bool textured = m >= ${Mat.TEXTURED};
    float layer = float(max(m - ${Mat.TEXTURED}, 0));
    // Derivatives and implicit-LOD lookups happen before any branch on the material.
    vec4 albedo = texture(uAlbedo, vec3(vUv, layer));
    vec2 relief = texture(uRelief, vec3(vUv, layer)).rg * 2.0 - 1.0;
    vec2 gradX = dFdx(vWorld.xz);
    vec2 gradY = dFdy(vWorld.xz);

    vec3 n = normalize(vNormal);
    vec3 c = vColor;
    float gloss = 0.0;
    bool emissive = false;
    if (textured) {
        gloss = albedo.a;
        c = albedo.rgb * mix(vColor, vec3(1.0), smoothstep(0.4, 0.7, gloss));
        n = normalize(n + (vTangent * relief.x + vBitangent * relief.y) * uReliefOn);
        // Contact shade where walls meet the pavement.
        if (abs(vBitangent.y) > 0.9) c *= mix(0.7, 1.0, clamp(vWorld.y / 2.5, 0.0, 1.0));
    } else if (m == ${Mat.GROUND}) {
        c = groundColor(vWorld, gradX, gradY);
    } else if (m == ${Mat.GLOSSY} || m == ${Mat.TINT}) {
        gloss = 0.5;
    } else if (m == ${Mat.GLASS}) {
        gloss = 0.95;
    } else if (m == ${Mat.WATER}) {
        gloss = 1.0;
        n = normalize(vec3(
            0.05 * sin(vWorld.x * 2.0 + uTime * 1.7) + 0.03 * sin(vWorld.z * 5.1 - uTime * 2.2), 1.0,
            0.05 * sin(vWorld.z * 2.3 + uTime * 1.3) + 0.03 * sin(vWorld.x * 4.3 + uTime * 2.6)));
    } else if (m == ${Mat.EMISSIVE}) {
        emissive = true;
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
                float lit = 0.5 * texture(uShadowMap, s + vec3(texel * 0.7, texel * 0.4, 0.0))
                          + 0.5 * texture(uShadowMap, s - vec3(texel * 0.7, texel * 0.4, 0.0));
                sun = mix(lit, 1.0, fade);
            }
        }
        vec3 ambient = mix(vec3(0.34, 0.34, 0.31), vec3(0.50, 0.57, 0.66), n.y * 0.5 + 0.5);
        c *= ambient + vec3(0.82, 0.74, 0.61) * facing * sun;
        if (gloss > 0.02) {
            vec3 look = vFromEye / d;
            vec3 bounced = reflect(look, n);
            float edge = 1.0 - max(dot(-look, n), 0.0);
            c = mix(c, surroundings(bounced), gloss * (0.16 + 0.6 * edge * edge * edge));
            float glint = max(dot(bounced, SUN), 0.0);
            glint *= glint; glint *= glint; glint *= glint; glint *= glint; glint *= glint;
            c += vec3(1.0, 0.92, 0.76) * glint * gloss * sun * 0.7;
        }
    }
    float f = d * 0.00165;
    float fog = 1.0 - exp(-f * f);
    if (fog > 0.003) c = mix(c, skyColor(vec3(vFromEye.x, max(vFromEye.y, 0.0) * 0.4, vFromEye.z) / d), fog);
    fragColor = vec4(c, 1.0);
}
"""

    const val SHADOW_VERTEX = HEADER + INSTANCE_TRANSFORM + """
void main() {
    gl_Position = uViewProjection * vec4((uModel * vec4(turn(aPosition), 1.0)).xyz + aPlace.xyz, 1.0);
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
void main() {
    vec3 d = normalize(mat3(uBasis) * vec3((vUv * 2.0 - 1.0) * uTan, -1.0));
    vec3 c = skyColor(d);
    if (d.y > 0.02) {
        vec2 p = d.xz / (d.y + 0.14) * 2.2 + vec2(uTime * 0.008, 0.0);
        float n = noise(p) * 0.57 + noise(p * 2.7) * 0.29 + noise(p * 6.1) * 0.14;
        c = mix(c, vec3(0.96, 0.97, 0.98), smoothstep(0.52, 0.78, n) * smoothstep(0.02, 0.2, d.y) * 0.8);
        c = mix(c, vec3(1.0, 0.96, 0.84), smoothstep(0.9992, 0.9996, dot(d, SUN)));
    }
    fragColor = vec4(c, 1.0);
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
