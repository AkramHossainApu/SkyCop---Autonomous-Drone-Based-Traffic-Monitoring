import Foundation

/// Metal versions of the shaders in Shaders.kt, compiled at runtime so the pod needs no resource bundle.
enum MetalShaders {
  static var source: String {
    let tiles = Tex.allCases.map { "float2(\($0.width), \($0.height))" }.joined(separator: ", ")
    return """
    #include <metal_stdlib>
    using namespace metal;

    constant float2 TILE[\(Tex.allCases.count)] = { \(tiles) };
    // normalize(0.45, 0.78, 0.38); Metal forbids computing it here.
    constant float3 SUN = float3(0.460408, 0.798040, 0.388789);

    struct Frame {
      float4x4 viewProjection;
      float4x4 light;
      float4 eye;          // xyz eye
      float4 params;       // time, signal time, shadows on, shadow texel
      float4 params2;      // relief on
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

    float3 skyColor(float3 d) {
      float h = clamp(d.y, 0.0, 1.0);
      float3 c = mix(float3(0.74, 0.82, 0.88), float3(0.22, 0.45, 0.74), sqrt(h));
      float glow = max(dot(d, SUN), 0.0);
      glow *= glow; glow *= glow; glow *= glow;
      return c + float3(1.0, 0.8, 0.55) * glow * 0.22;
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

    // Bare ground between the mapped roads and buildings: dusty earth with patches of grass.
    float3 groundColor(float3 p, float2 gradX, float2 gradY, texture2d_array<float> albedo, sampler s) {
      float g = \(Tex.grass.width);
      float3 grass = albedo.sample(s, p.xz / g, \(Tex.grass.rawValue), gradient2d(gradX / g, gradY / g)).rgb;
      float d = \(Tex.concrete.width);
      float3 dirt = albedo.sample(s, p.xz / d, \(Tex.concrete.rawValue), gradient2d(gradX / d, gradY / d)).rgb * float3(0.93, 0.85, 0.72);
      float large = 37.0;
      float broad = albedo.sample(s, p.xz / large, \(Tex.grass.rawValue), gradient2d(gradX / large, gradY / large)).g;
      float patches = smoothstep(0.38, 0.62, broad);
      return mix(dirt, grass * 0.92, patches * 0.6) * (0.82 + broad * 0.36);
    }

    float3 surroundings(float3 d) {
      return mix(float3(0.24, 0.28, 0.3), skyColor(d), smoothstep(-0.12, 0.06, d.y));
    }

    fragment float4 worldFragment(WorldOut in [[stage_in]], constant Frame& frame [[buffer(2)]],
                                  texture2d_array<float> albedoMap [[texture(0)]], texture2d_array<float> reliefMap [[texture(1)]],
                                  depth2d<float> shadowMap [[texture(2)]], sampler linear [[sampler(0)]], sampler compare [[sampler(1)]]) {
      int m = in.material;
      bool textured = m >= \(Mat.TEXTURED);
      uint layer = uint(max(m - \(Mat.TEXTURED), 0));
      float4 albedo = albedoMap.sample(linear, in.uv, layer);
      float2 relief = reliefMap.sample(linear, in.uv, layer).rg * 2.0 - 1.0;
      float2 gradX = dfdx(in.world.xz);
      float2 gradY = dfdy(in.world.xz);
      float time = frame.params.x;

      float3 n = normalize(in.normal);
      float3 c = in.color;
      float gloss = 0.0;
      bool emissive = false;
      if (textured) {
        gloss = albedo.a;
        c = albedo.rgb * mix(in.color, float3(1.0), smoothstep(0.4, 0.7, gloss));
        n = normalize(n + (in.tangent * relief.x + in.bitangent * relief.y) * frame.params2.x);
        if (abs(in.bitangent.y) > 0.9) c *= mix(0.7, 1.0, clamp(in.world.y / 2.5, 0.0, 1.0));
      } else if (m == \(Mat.GROUND)) {
        c = groundColor(in.world, gradX, gradY, albedoMap, linear);
      } else if (m == \(Mat.GLOSSY) || m == \(Mat.TINT)) {
        gloss = 0.5;
      } else if (m == \(Mat.GLASS)) {
        gloss = 0.95;
      } else if (m == \(Mat.WATER)) {
        gloss = 1.0;
        n = normalize(float3(
          0.05 * sin(in.world.x * 2.0 + time * 1.7) + 0.03 * sin(in.world.z * 5.1 - time * 2.2), 1.0,
          0.05 * sin(in.world.z * 2.3 + time * 1.3) + 0.03 * sin(in.world.x * 4.3 + time * 2.6)));
      } else if (m == \(Mat.EMISSIVE)) {
        emissive = true;
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
            float lit = 0.5 * shadowMap.sample_compare(compare, s.xy + float2(texel * 0.7, texel * 0.4), s.z)
                      + 0.5 * shadowMap.sample_compare(compare, s.xy - float2(texel * 0.7, texel * 0.4), s.z);
            sun = mix(lit, 1.0, fade);
          }
        }
        float3 ambient = mix(float3(0.34, 0.34, 0.31), float3(0.50, 0.57, 0.66), n.y * 0.5 + 0.5);
        c *= ambient + float3(0.82, 0.74, 0.61) * facing * sun;
        if (gloss > 0.02) {
          float3 look = in.fromEye / d;
          float3 bounced = reflect(look, n);
          float edge = 1.0 - max(dot(-look, n), 0.0);
          c = mix(c, surroundings(bounced), gloss * (0.16 + 0.6 * edge * edge * edge));
          float glint = max(dot(bounced, SUN), 0.0);
          glint *= glint; glint *= glint; glint *= glint; glint *= glint; glint *= glint;
          c += float3(1.0, 0.92, 0.76) * glint * gloss * sun * 0.7;
        }
      }
      float f = d * 0.00165;
      float fog = 1.0 - exp(-f * f);
      if (fog > 0.003) c = mix(c, skyColor(float3(in.fromEye.x, max(in.fromEye.y, 0.0) * 0.4, in.fromEye.z) / d), fog);
      return float4(c, 1.0);
    }

    vertex float4 shadowVertex(VertexIn in [[stage_in]], constant Frame& frame [[buffer(2)]], constant float4x4& model [[buffer(3)]]) {
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

    struct SkyParams { float4x4 basis; float4 tanTime; };

    fragment float4 skyFragment(FullOut in [[stage_in]], constant SkyParams& sky [[buffer(0)]]) {
      float3x3 basis = float3x3(sky.basis[0].xyz, sky.basis[1].xyz, sky.basis[2].xyz);
      float3 d = normalize(basis * float3((in.uv * 2.0 - 1.0) * sky.tanTime.xy, -1.0));
      float3 c = skyColor(d);
      if (d.y > 0.02) {
        float2 p = d.xz / (d.y + 0.14) * 2.2 + float2(sky.tanTime.z * 0.008, 0.0);
        float n = noise(p) * 0.57 + noise(p * 2.7) * 0.29 + noise(p * 6.1) * 0.14;
        c = mix(c, float3(0.96, 0.97, 0.98), smoothstep(0.52, 0.78, n) * smoothstep(0.02, 0.2, d.y) * 0.8);
        c = mix(c, float3(1.0, 0.96, 0.84), smoothstep(0.9992, 0.9996, dot(d, SUN)));
      }
      return float4(c, 1.0);
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
