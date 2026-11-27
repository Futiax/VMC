#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);

    // 1. Warm Golden Sepia Cinema tone
    float gray = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 sepia = vec3(gray) * vec3(1.2, 0.9, 0.6);

    // 2. Vignette
    vec2 uv = texCoord - 0.5;
    float dist = length(uv);
    float vignette = smoothstep(0.7, 0.2, dist);
    sepia *= mix(0.5, 1.0, vignette);

    fragColor = vec4(clamp(sepia, 0.0, 1.0), color.a);
}
