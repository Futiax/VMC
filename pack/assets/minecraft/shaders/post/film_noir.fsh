#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);

    // 1. Grayscale + Negative Invert for striking visual test
    float gray = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float inverted = 1.0 - gray;

    // 2. High contrast film curve
    inverted = (inverted - 0.5) * 1.4 + 0.5;
    inverted = clamp(inverted, 0.0, 1.0);

    // 3. Vignette
    vec2 uv = texCoord - 0.5;
    float dist = length(uv);
    float vignette = smoothstep(0.7, 0.2, dist);
    inverted *= mix(0.4, 1.0, vignette);

    fragColor = vec4(vec3(inverted), color.a);
}
