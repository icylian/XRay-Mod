#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in float vertexViewDistance;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor;

    // Vein highlight: a negative red channel in ColorModulator marks the highlighted
    // vein. The hue is inverted from the user color (with a brightness floor so dark
    // inverted hues stay visible); the user's original alpha is kept as-is.
    if (ColorModulator.r < 0.0) {
        vec3 inverted = vec3(1.0) - color.rgb;
        // Push away from gray so dark/near-black inverted hues stay visible
        float luminance = dot(inverted, vec3(0.299, 0.587, 0.114));
        color.rgb = inverted + max(vec3(0.0), (0.45 - luminance)) * (inverted - vec3(luminance) + vec3(0.0001));
    } else {
        color.rgb *= ColorModulator.rgb;
        color.a *= ColorModulator.a;

        // ModelOffset carries the distance-fade parameters: x = fade start,
        // y = fade end, z = minimum alpha multiplier.
        float fade = clamp((vertexViewDistance - ModelOffset.x) / max(ModelOffset.y - ModelOffset.x, 0.001), 0.0, 1.0);
        color.a *= mix(1.0, ModelOffset.z, fade);
    }

    fragColor = color;
}
