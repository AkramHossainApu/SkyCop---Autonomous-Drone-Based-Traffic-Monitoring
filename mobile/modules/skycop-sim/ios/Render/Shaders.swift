import Foundation

/// Metal versions of the shaders in Shaders.kt, compiled at runtime so the pod needs no resource bundle.
enum MetalShaders {
  static var source: String {
    let tiles = Tex.allCases.map { "float2(\($0.width), \($0.height))" }.joined(separator: ", ")
    return """
    #include <metal_stdlib>
    using namespace metal;

    constant float2 TILE[\(Tex.allCases.count)] = { \(tiles) };
    // Set every frame from Environment (see Shaders.kt): light, its color, the sky, haze, night, wetness, rain.
    struct Env {
      float4 sun;       // xyz toward the sun or moon, w night
      float4 sunColor;  // rgb light color, w wetness
      float4 skyTop;    // rgb, w haze density
      float4 skyLow;    // rgb, w rain
    };

    struct Frame {
      float4x4 viewProjection;
      float4x4 light;
      float4 eye;          // xyz eye
      float4 params;       // time, signal time, shadows on, shadow texel
      float4 params2;      // relief on
      Env env;
    };

    struct VertexIn {
      float3 position [[attribute(0)]];
      float4 normal [[attribute(1)]];
      float4 color [[attribute(2)]];
      float4 place [[attribute(3)]];
      float4 tint [[attribute(4)]];
    };

    struct WorldOut {
      float4 position [[position]];
      float3 world;
      float3 fromEye;
      float4 shadow;
      float3 normal;
      float2 uv;
      float3 tangent [[flat]];
      float3 bitangent [[flat]];
      float3 color [[flat]];
      int material [[flat]];
    };

    float gmod(float x, float y) { return x - y * floor(x / y); }

    // Dhaka's sky: a dusty haze at the horizon fading to the sky overhead, with a glow around the sun.
    float3 skyColor(float3 d, Env e) {
      float h = clamp(d.y, 0.0, 1.0);
      float3 c = mix(e.skyLow.rgb, e.skyTop.rgb, pow(h, 0.62));
      float glow = max(dot(d, e.sun.xyz), 0.0);
      float halo = glow * glow; halo *= halo; halo *= halo;
      return c + e.sunColor.rgb * (halo * 0.08 + pow(glow, 40.0) * 0.06) * (1.0 - e.sun.w);
    }

    // Filmic curve (ACES fit) from linear light to the screen, with a slight warm grade.
    float3 film(float3 x) {
      x *= float3(0.56, 0.54, 0.51);
      x = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
      x = clamp(mix(float3(dot(x, float3(0.3, 0.55, 0.15))), x, 1.12), 0.0, 1.0);
      return sqrt(x);
    }

    float hash(float2 p) {
      p = fract(p * float2(0.1031, 0.1030));
      p += dot(p, p.yx + 33.33);
      return fract((p.x + p.y) * p.x);
    }
    float noise(float2 p) {
      float2 i = floor(p); float2 f = fract(p);
      f = f * f * (3.0 - 2.0 * f);
      return mix(mix(hash(i), hash(i + float2(1, 0)), f.x), mix(hash(i + float2(0, 1)), hash(i + float2(1, 1)), f.x), f.y);
    }

    float3 turn(float3 p, float yaw) {
      float c = cos(yaw); float s = sin(yaw);
      return float3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
    }

    vertex WorldOut worldVertex(VertexIn in [[stage_in]], constant Frame& frame [[buffer(2)]], constant float4x4& model [[buffer(3)]]) {
      WorldOut out;
      float3 world = (model * float4(turn(in.position, in.place.w), 1.0)).xyz + in.place.xyz;
      float3x3 rotation = float3x3(model[0].xyz, model[1].xyz, model[2].xyz);
      out.normal = rotation * turn(in.normal.xyz, in.place.w);
      int face = int(in.normal.w * 127.0 + 0.5);
      float3 tangent; float3 bitangent = float3(0.0, 1.0, 0.0);
      if (face == 0) tangent = float3(0.0, 0.0, -1.0);
      else if (face == 1) tangent = float3(0.0, 0.0, 1.0);
      else if (face == 2) tangent = float3(1.0, 0.0, 0.0);
      else if (face == 3) tangent = float3(-1.0, 0.0, 0.0);
      else if (face == 6) tangent = normalize(float3(in.normal.z, 0.0, -in.normal.x));
      else { tangent = float3(1.0, 0.0, 0.0); bitangent = float3(0.0, 0.0, face == 4 ? -1.0 : 1.0); }
      int material = int(in.color.a * 255.0 + 0.5);
      if (material == \(Mat.LEDGE)) material = \(Mat.TEXTURED + Tex.concrete.rawValue);
      out.uv = float2(0.0);
      if (material >= \(Mat.TEXTURED)) {
        float2 at = float2(dot(in.position, tangent), dot(in.position, bitangent));
        if (face < 4 || face == 6) at.y -= \(City.CURB);
        out.uv = at / TILE[material - \(Mat.TEXTURED)];
      }
      out.tangent = rotation * turn(tangent, in.place.w);
      out.bitangent = rotation * turn(bitangent, in.place.w);
      out.color = material == \(Mat.TINT) ? in.color.rgb * in.tint.rgb : in.color.rgb;
      out.material = material;
      out.world = world;
      out.fromEye = world - frame.eye.xyz;
      out.shadow = frame.light * float4(world + normalize(out.normal) * 0.09, 1.0);
      out.position = frame.viewProjection * float4(world, 1.0);
      return out;
    }

    float band(float distanceFromCenter, float halfWidth, float px) {
      return clamp((halfWidth - abs(distanceFromCenter)) / px + 0.5, 0.0, 1.0) * min(1.0, 2.0 * halfWidth / px);
    }

    // Bare ground between the mapped roads and buildings: Dhaka's packed dusty earth, brick dust and
    // worn patches of grass.
    float3 groundColor(float3 p, float2 gradX, float2 gradY, texture2d_array<float> albedo, sampler s) {
      float g = \(Tex.grass.width);
      float3 grass = albedo.sample(s, p.xz / g, \(Tex.grass.rawValue), gradient2d(gradX / g, gradY / g)).rgb;
      float d = \(Tex.concrete.width);
      float3 dirt = albedo.sample(s, p.xz / d, \(Tex.concrete.rawValue), gradient2d(gradX / d, gradY / d)).rgb * float3(0.78, 0.72, 0.63);
      float large = 37.0;
      float broad = albedo.sample(s, p.xz / large, \(Tex.grass.rawValue), gradient2d(gradX / large, gradY / large)).g;
      float patches = smoothstep(0.3, 0.58, broad);
      // Mostly overgrown with weeds and grass, as empty plots in Dhaka are, with bare dusty tracks.
      float3 weeds = mix(grass, float3(0.42, 0.47, 0.3), 0.35) * 0.8;
      float3 c = mix(dirt, weeds, patches * 0.85) * (0.78 + broad * 0.36);
      float stain = noise(p.xz * 0.09);
      c = mix(c, float3(0.55, 0.40, 0.32), smoothstep(0.62, 0.9, stain) * 0.35);
      return c * mix(1.0, 0.72, smoothstep(0.55, 0.15, stain));
    }

    float3 surroundings(float3 d, Env e) {
      return mix(float3(0.24, 0.26, 0.27) * (1.0 - 0.85 * e.sun.w), skyColor(d, e), smoothstep(-0.12, 0.06, d.y));
    }

    fragment float4 worldFragment(WorldOut in [[stage_in]], constant Frame& frame [[buffer(2)]],
                                  texture2d_array<float> albedoMap [[texture(0)]], texture2d_array<float> reliefMap [[texture(1)]],
                                  depth2d<float> shadowMap [[texture(2)]], sampler linear [[sampler(0)]], sampler compare [[sampler(1)]]) {
      int m = in.material;
      bool textured = m >= \(Mat.TEXTURED);
      int layerId = max(m - \(Mat.TEXTURED), 0);
      uint layer = uint(layerId);
      float4 albedo = albedoMap.sample(linear, in.uv, layer);
      float2 relief = reliefMap.sample(linear, in.uv, layer).rg * 2.0 - 1.0;
      float2 gradX = dfdx(in.world.xz);
      float2 gradY = dfdy(in.world.xz);
      float time = frame.params.x;
      float reliefOn = frame.params2.x;
      Env e = frame.env;
      float3 SUN = e.sun.xyz;

      float3 n = normalize(in.normal);
      float3 c = in.color;
      float gloss = 0.0;
      float occlusion = 1.0;
      bool emissive = false;
      float3 glow = float3(0.0);
      if (textured) {
        gloss = albedo.a;
        c = albedo.rgb * mix(in.color, float3(1.0), smoothstep(0.4, 0.7, gloss));
        n = normalize(n + (in.tangent * relief.x + in.bitangent * relief.y) * reliefOn);
        if (layerId == \(Tex.leaves.rawValue)) c *= float3(0.74, 0.8, 0.68);
        if (layerId == \(Tex.grass.rawValue)) {
          float lush = noise(in.world.xz * 0.06) * 0.6 + noise(in.world.xz * 0.25) * 0.4;
          c = mix(c, float3(0.5, 0.5, 0.33), 0.3 + 0.3 * smoothstep(0.6, 0.2, lush)) * (0.78 + 0.3 * lush);
        }
        bool wall = abs(in.bitangent.y) > 0.9;
        bool built = layerId == \(Tex.concrete.rawValue) || (layerId >= \(Tex.brick.rawValue) && layerId <= \(Tex.roofTiles.rawValue));
        if (wall) {
          float up = in.world.y - \(City.CURB);
          c *= mix(0.72, 1.0, clamp(in.world.y / 2.5, 0.0, 1.0));
          occlusion = mix(0.6, 1.0, smoothstep(0.0, 18.0, in.world.y));
          if (built) {
            float along = dot(in.world, in.tangent);
            float plaster = 1.0 - smoothstep(0.4, 0.7, gloss);
            // The sky hidden under each floor's sunshade.
            float underShade = gmod(2.72 - up, 3.3);
            occlusion *= 1.0 - (1.0 - smoothstep(0.0, 0.6, underShade)) * 0.4 * step(3.0, up);
            c *= 1.0 - (1.0 - smoothstep(0.0, 0.25, underShade)) * 0.2 * step(3.0, up) * plaster;
            if (reliefOn > 0.5) {
              float streaks = noise(float2(along * 1.7, in.world.y * 0.06));
              float blotch = noise(float2(along * 0.13, up * 0.11) + 19.0);
              float dirt = smoothstep(0.5, 1.0, streaks) * 0.32 * smoothstep(0.3, 0.8, blotch + 0.2)
                         + smoothstep(0.55, 0.95, blotch) * 0.1
                         + (1.0 - smoothstep(0.0, 2.6, up)) * 0.2;
              c = mix(c, c * float3(0.55, 0.56, 0.5), clamp(dirt, 0.0, 1.0) * plaster);
            }
            if (gloss > 0.6) {
              float h = hash(floor(float2(along / 3.0, up / 3.3)) + floor(in.world.xz * 0.03) * 7.0);
              if (h < 0.3) c *= 0.5;
              else if (h < 0.42) c = mix(c, float3(0.75, 0.55, 0.32), 0.4);
              else if (h < 0.54) c = mix(c, h < 0.48 ? float3(0.72, 0.48, 0.5) : float3(0.45, 0.6, 0.66), 0.45);
              gloss *= 0.55 + h * 0.5;
              if (h > 0.3 && h < 0.62) glow = float3(1.0, 0.76, 0.42) * (0.45 + h * 0.7) * e.sun.w;
            }
          }
        } else if (built && reliefOn > 0.5) {
          float stain = noise(in.world.xz * 0.21) * 0.6 + noise(in.world.xz * 0.9) * 0.4;
          c *= 1.0 - smoothstep(0.45, 0.85, stain) * 0.3;
        } else if (layerId == \(Tex.asphalt.rawValue)) {
          float patchwork = noise(in.world.xz * 0.07);
          float dust = noise(in.world.xz * 0.21 + 5.0);
          c *= 0.84 + patchwork * 0.3;
          c = mix(c, float3(0.56, 0.53, 0.47), smoothstep(0.55, 0.95, dust) * 0.4);
        }
      } else if (m == \(Mat.GROUND)) {
        c = groundColor(in.world, gradX, gradY, albedoMap, linear);
      } else if (m == \(Mat.STRIPED)) {
        float block = gmod(floor(in.world.x * 1.1) + floor(in.world.z * 1.1), 2.0);
        c = block < 0.5 ? in.color : float3(0.13, 0.13, 0.12);
        c *= 0.78 + 0.22 * noise(in.world.xz * 3.7 + in.world.y * 5.0);
        gloss = 0.08;
      } else if (m == \(Mat.GLOSSY) || m == \(Mat.TINT)) {
        gloss = 0.5;
      } else if (m == \(Mat.GLASS)) {
        gloss = 0.95;
      } else if (m == \(Mat.WATER)) {
        gloss = 1.0;
        n = normalize(float3(
          0.05 * sin(in.world.x * 2.0 + time * 1.7) + 0.03 * sin(in.world.z * 5.1 - time * 2.2), 1.0,
          0.05 * sin(in.world.z * 2.3 + time * 1.3) + 0.03 * sin(in.world.x * 4.3 + time * 2.6)));
        c *= float3(0.55, 0.6, 0.5);
      } else if (m == \(Mat.EMISSIVE)) {
        emissive = true;
      } else if (m == \(Mat.LAMP)) {
        c *= mix(0.72, 1.35, e.sun.w); emissive = true;
      } else if (m == \(Mat.STROBE)) {
        float blink = step(0.5, fract(time * 2.0 + (in.color.r > 0.5 ? 0.0 : 0.5)));
        c *= 0.3 + blink; emissive = true;
      } else if (m >= \(Mat.SIGNAL)) {
        int id = m - \(Mat.SIGNAL);
        int group = id / 6; int axis = (id % 6) / 3; int light = id % 3;
        float t = gmod(frame.params.y + float(group) * 6.5 + float(axis) * 13.0, \(Signals.CYCLE));
        int lit = t < \(Signals.GREEN) ? 0 : (t < \(Signals.YELLOW) ? 1 : 2);
        c *= light == lit ? 1.25 : 0.13; emissive = true;
      }

      float wetness = e.sunColor.w;
      if (wetness > 0.01 && !emissive && m != \(Mat.WATER)) {
        if (in.normal.y > 0.75 && !(textured && (layerId == \(Tex.grass.rawValue) || layerId == \(Tex.leaves.rawValue)))) {
          float puddle = smoothstep(0.6, 0.74, noise(in.world.xz * 0.23) * 0.7 + noise(in.world.xz * 0.9) * 0.3) * wetness;
          c *= mix(1.0, 0.6, wetness);
          gloss = max(gloss, wetness * 0.32 + puddle * 0.6);
          n = normalize(mix(n, float3(0.0, 1.0, 0.0), puddle));
          float ripple = e.skyLow.w * puddle * 0.12;
          n = normalize(n + float3(noise(in.world.xz * 7.0 + time * 3.1) - 0.5, 0.0, noise(in.world.zx * 7.0 - time * 2.7) - 0.5) * ripple);
        } else if (textured) {
          c *= 1.0 - 0.16 * wetness;
        }
      }

      float d = length(in.fromEye);
      if (!emissive) {
        float facing = max(dot(n, SUN), 0.0);
        float sun = 1.0;
        float3 s = float3(in.shadow.x * 0.5 + 0.5, 0.5 - in.shadow.y * 0.5, in.shadow.z);
        if (frame.params.z > 0.5 && facing > 0.0 && s.z < 1.0) {
          float2 e = abs(s.xy - 0.5) * 2.0;
          float fade = smoothstep(0.85, 1.0, max(e.x, e.y));
          if (fade < 1.0) {
            float texel = frame.params.w;
            float lit = 0.25 * (shadowMap.sample_compare(compare, s.xy + float2(texel * 1.6, texel * 0.6), s.z)
                              + shadowMap.sample_compare(compare, s.xy - float2(texel * 1.6, texel * 0.6), s.z)
                              + shadowMap.sample_compare(compare, s.xy + float2(-texel * 0.6, texel * 1.6), s.z)
                              + shadowMap.sample_compare(compare, s.xy + float2(texel * 0.6, -texel * 1.6), s.z));
            sun = mix(lit, 1.0, fade);
          }
        }
        float3 base = c * c;
        float3 skyLight = mix(e.skyLow.rgb, e.skyTop.rgb, 0.6) * 0.85;
        float3 ambient = mix(skyLight * float3(0.45, 0.38, 0.27), skyLight, n.y * 0.5 + 0.5) * occlusion;
        ambient += float3(0.09, 0.065, 0.04) * e.sun.w * occlusion * (1.0 - 0.5 * smoothstep(0.0, 40.0, in.world.y));
        float3 light = base * (ambient + e.sunColor.rgb * facing * sun);
        if (gloss > 0.02) {
          float3 look = in.fromEye / d;
          float3 bounced = reflect(look, n);
          float edge = 1.0 - max(dot(-look, n), 0.0);
          float3 mirror = surroundings(bounced, e);
          light = mix(light, mirror * mirror * 1.6 * occlusion, gloss * (0.14 + 0.62 * edge * edge * edge));
          float glint = max(dot(bounced, SUN), 0.0);
          glint *= glint; glint *= glint; glint *= glint; glint *= glint; glint *= glint;
          light += e.sunColor.rgb * glint * gloss * sun;
        }
        c = film(light * (1.0 + 1.1 * e.sun.w)) + glow;
      }
      float f = d * e.skyTop.w;
      float fog = 1.0 - exp(-f * f);
      if (fog > 0.003) c = mix(c, skyColor(float3(in.fromEye.x, max(in.fromEye.y, 0.0) * 0.4, in.fromEye.z) / d, e), fog);
      return float4(c, 1.0);
    }

    vertex float4 shadowVertex(VertexIn in [[stage_in]], constant Frame& frame [[buffer(2)]], constant float4x4& model [[buffer(3)]]) {
      // Sunshades stay out of the shadow map; the facade shader shades under them instead.
      if (int(in.color.a * 255.0 + 0.5) == \(Mat.LEDGE)) return float4(2.0, 2.0, 2.0, 1.0);
      return frame.light * float4((model * float4(turn(in.position, in.place.w), 1.0)).xyz + in.place.xyz, 1.0);
    }

    struct FullOut { float4 position [[position]]; float2 uv; };

    vertex FullOut fullscreenVertex(uint id [[vertex_id]]) {
      float2 p = float2(float((id << 1) & 2), float(id & 2));
      FullOut out;
      out.uv = p;
      out.position = float4(p * 2.0 - 1.0, 1.0, 1.0);
      return out;
    }

    struct SkyParams { float4x4 basis; float4 tanTime; Env env; };

    fragment float4 skyFragment(FullOut in [[stage_in]], constant SkyParams& sky [[buffer(0)]]) {
      float3x3 basis = float3x3(sky.basis[0].xyz, sky.basis[1].xyz, sky.basis[2].xyz);
      float3 d = normalize(basis * float3((in.uv * 2.0 - 1.0) * sky.tanTime.xy, -1.0));
      Env e = sky.env;
      float3 SUN = e.sun.xyz;
      float3 c = skyColor(d, e);
      if (d.y > 0.02) {
        float2 p = d.xz / (d.y + 0.14) * 2.2 + float2(sky.tanTime.z * 0.008, 0.0);
        float n = noise(p) * 0.57 + noise(p * 2.7) * 0.29 + noise(p * 6.1) * 0.14;
        float rain = e.skyLow.w;
        float3 cloud = mix(float3(0.96, 0.97, 0.98), e.skyLow.rgb * 1.05, 0.35 + 0.35 * rain) * (1.0 - 0.85 * e.sun.w);
        c = mix(c, cloud, smoothstep(0.52 - 0.3 * rain, 0.78 - 0.2 * rain, n) * smoothstep(0.02, 0.2, d.y) * (0.8 + 0.15 * rain));
        float open = (1.0 - rain) * (1.0 - e.sun.w);
        c = mix(c, float3(1.0, 0.96, 0.84), smoothstep(0.9992, 0.9996, dot(d, SUN)) * open);
        float star = step(0.9965, hash(floor(d.xz / (d.y + 0.3) * 260.0)));
        c += float3(0.75, 0.8, 0.9) * star * e.sun.w * (1.0 - rain) * smoothstep(0.1, 0.4, d.y);
        c = mix(c, float3(0.92, 0.93, 0.88), smoothstep(0.9993, 0.9996, dot(d, SUN)) * e.sun.w * (1.0 - rain));
      }
      return float4(c, 1.0);
    }

    struct ShadeOut { float4 position [[position]]; float shade; };

    // Soft shadow decals, multiplied over the city, fading out into the haze like everything else.
    vertex ShadeOut shadeVertex(VertexIn in [[stage_in]], constant Frame& frame [[buffer(2)]]) {
      ShadeOut out;
      float f = length(in.position - frame.eye.xyz) * frame.env.skyTop.w;
      out.shade = mix(in.color.r, 1.0, 1.0 - exp(-f * f));
      out.position = frame.viewProjection * float4(in.position, 1.0);
      return out;
    }

    fragment float4 shadeFragment(ShadeOut in [[stage_in]]) {
      return float4(float3(in.shade), 1.0);
    }

    // Lamp light pools: the decal's gray turned into added warm light (0 is the brightest).
    fragment float4 poolFragment(ShadeOut in [[stage_in]], constant float4& tint [[buffer(0)]]) {
      return float4(tint.rgb * (1.0 - in.shade), 1.0);
    }

    struct RainOut { float4 position [[position]]; float along; };
    struct RainParams { float4x4 viewProjection; float4 eyeTime; float4 right; float4 color; };

    // Monsoon rain streaks (see Shaders.kt): a 50 m box that travels with the viewer.
    vertex RainOut rainVertex(uint id [[vertex_id]], const device float4* corners [[buffer(0)]], constant RainParams& r [[buffer(1)]]) {
      float4 corner = corners[id];
      float3 box = corner.xyz;
      float fall = 9.0 + fract(box.x * 7.13) * 3.0;
      float3 eye = r.eyeTime.xyz;
      float3 p = float3(box.x, box.y - r.eyeTime.w * fall, box.z);
      float3 q = p - eye + 25.0;
      p = q - 50.0 * floor(q / 50.0) - 25.0 + eye;
      float3 slant = normalize(float3(0.18, -1.0, 0.08));
      int k = int(corner.w + 0.5);
      float along = (k == 1 || k == 2) ? 1.0 : 0.0;
      float side = (k >= 2) ? 1.0 : -1.0;
      p += slant * along * 0.75 + r.right.xyz * side * 0.012;
      RainOut out;
      out.along = along;
      out.position = r.viewProjection * float4(p, 1.0);
      return out;
    }

    fragment float4 rainFragment(RainOut in [[stage_in]], constant RainParams& r [[buffer(1)]]) {
      return float4(r.color.rgb, r.color.a * (0.35 + 0.65 * in.along));
    }

    struct GlowOut { float4 position [[position]]; float3 color; };

    vertex GlowOut glowVertex(VertexIn in [[stage_in]], constant Frame& frame [[buffer(2)]], constant float4x4& model [[buffer(3)]]) {
      GlowOut out;
      out.color = in.color.rgb;
      out.position = frame.viewProjection * model * float4(in.position, 1.0);
      return out;
    }

    fragment float4 glowFragment(GlowOut in [[stage_in]], constant float4& glow [[buffer(0)]]) {
      return float4(in.color * glow.rgb, glow.a);
    }

    struct HudIn { float2 position [[attribute(0)]]; float2 uv [[attribute(1)]]; float4 color [[attribute(2)]]; };
    struct HudOut { float4 position [[position]]; float2 uv; float4 color; };

    vertex HudOut hudVertex(HudIn in [[stage_in]], constant float2& halfSize [[buffer(1)]]) {
      HudOut out;
      out.uv = in.uv; out.color = in.color;
      out.position = float4(in.position.x / halfSize.x, -in.position.y / halfSize.y, 0.0, 1.0);
      return out;
    }

    fragment float4 hudFragment(HudOut in [[stage_in]], texture2d<float> font [[texture(0)]], sampler s [[sampler(0)]]) {
      return float4(in.color.rgb, in.color.a * font.sample(s, in.uv).r);
    }

    struct Present { float4 rect; float4 texelAspectShift; float4 lensVignette; };

    fragment float4 presentFragment(FullOut in [[stage_in]], constant Present& p [[buffer(0)]],
                                    texture2d<float> image [[texture(0)]], sampler s [[sampler(0)]]) {
      float2 d = in.uv * 2.0 - 1.0;
      d.x -= p.texelAspectShift.w;
      float aspect = p.texelAspectShift.z;
      float2 scale = aspect > 1.0 ? float2(aspect, 1.0) : float2(1.0, 1.0 / aspect);
      float2 q = d * scale;
      float r2 = dot(q, q);
      float2 lens = p.lensVignette.xy;
      float2 source = d * (1.0 + lens.x * r2 + lens.y * r2 * r2) / (1.0 + lens.x + lens.y);
      if (abs(source.x) > 1.0 || abs(source.y) > 1.0) return float4(0.0, 0.0, 0.0, 1.0);
      // Metal textures start at the top row.
      float2 uv = float2(p.rect.x + (source.x * 0.5 + 0.5) * p.rect.z, (0.5 - source.y * 0.5) * p.rect.w);
      uv = clamp(uv, float2(p.rect.x, 0.0) + p.texelAspectShift.xy, float2(p.rect.x + p.rect.z, p.rect.w) - p.texelAspectShift.xy);
      float3 c = image.sample(s, uv).rgb;
      c *= 1.0 - p.lensVignette.z * smoothstep(0.55, 1.05, sqrt(r2));
      return float4(c, 1.0);
    }
    """
  }
}
