#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
uniform sampler2D m_ColorLut;
varying vec2 texCoord;
void main() {
    vec4 source = getColor(m_Texture,texCoord);
    vec3 code = clamp((log2(max(source.rgb,vec3(0.0))/.18+exp2(-6.5))+6.5)/14.0,0.0,1.0)*31.0;
    float slice = floor(code.b);
    vec2 uv = vec2((code.r+.5+slice*32.0)/1024.0,(code.g+.5)/32.0);
    vec3 lo = texture2D(m_ColorLut,uv).rgb;
    uv.x = (code.r+.5+min(slice+1.0,31.0)*32.0)/1024.0;
    gl_FragColor = vec4(mix(lo,texture2D(m_ColorLut,uv).rgb,fract(code.b)),source.a);
}
