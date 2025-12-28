#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex; // The light map texture sampler

// Custom Uniform for Waves
uniform float u_Time;
uniform float u_WaveAmplitude;
uniform float u_WaveFrequency;
uniform float u_WaveSpeed;
uniform vec2 u_WaveDirection;
uniform vec2 u_CameraPosition;

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

    // Custom Wave Logic
    // Check for the specific alpha value set in SodiumFluidRenderMixin (254/255 ~= 0.996)
    if (_vert_color.a > 0.995 && _vert_color.a < 0.997) {
        // Calculate wave
        // Wave moving in direction D: sin(dot(P, D) * freq - time * speed)
        // position is camera-relative, so add camera position to get world position
        vec2 worldPos = position.xz + u_CameraPosition;
        
        float wave = sin(dot(worldPos, u_WaveDirection) * u_WaveFrequency - u_Time * u_WaveSpeed);
        float normalized = (wave + 1.0) / 2.0; // [0, 1]
        float offset = normalized * u_WaveAmplitude; // [0, Amplitude]
        
        position.y -= offset;
    }

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
