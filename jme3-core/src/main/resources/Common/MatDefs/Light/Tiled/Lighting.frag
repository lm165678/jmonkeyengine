#define textureCubeLod textureLod
#define textureCube texture
#define texture2D texture
#define mat3_sub mat3
#import "Common/ShaderLib/BlinnPhongLighting.glsllib"
#import "Common/ShaderLib/PBR.glsllib"
#import "Common/ShaderLib/Lighting.glsllib"
uniform sampler2D Albedo, Normal, Specular, Ambient, Reflection, Depth, Lights, Tiles, ColorRamp;
uniform bool Untiled;
uniform mat4 InverseViewProjection;
uniform vec3 CameraPosition, AmbientColor;
uniform vec2 Resolution;
uniform int Columns, TileSize, LightCount, TileTextureWidth, TileCapacity;
#ifdef GPU_TILES
layout(std430) buffer TileIndices { int tileRecords[]; };
int readIndex(int address){return tileRecords[address];}
#else
int readIndex(int address){return int(texelFetch(Tiles,ivec2(address%TileTextureWidth,address/TileTextureWidth),0).r);}
#endif
layout(location=0) out vec4 outFragColor;
void main(){
 ivec2 pixel=ivec2(gl_FragCoord.xy);
 vec4 base=texelFetch(Albedo,pixel,0);
 if(base.a<0.5) discard;
 float depth=texelFetch(Depth,pixel,0).r;
 vec4 world=InverseViewProjection*vec4(gl_FragCoord.xy/Resolution*2.0-1.0,depth*2.0-1.0,1.0);
 vec3 position=world.xyz/world.w;
 vec4 n=texelFetch(Normal,pixel,0);
 vec3 normal=normalize(n.xyz), viewDir=normalize(CameraPosition-position);
 vec4 specData=texelFetch(Specular,pixel,0);
 vec3 spec=specData.rgb;
 vec4 extra=texelFetch(Ambient,pixel,0);
 vec4 reflection=texelFetch(Reflection,pixel,0);
 vec3 result=int(base.a)<=3?extra.rgb:extra.rgb*AmbientColor;
 int flags=int(reflection.a);

 int tile=(pixel.y/TileSize)*Columns+pixel.x/TileSize;
 #ifdef GPU_TILES
 int offset=tile*(TileCapacity+1)+1;
 int count=readIndex(offset-1);
 bool overflow=count>TileCapacity;
 if(overflow)count=LightCount;
 #else
 int offset=0,count=LightCount;
 if(!Untiled){offset=readIndex(tile*2);count=readIndex(tile*2+1);}
 #endif
 if(Untiled)count=LightCount;
 for(int j=0;j<count && int(base.a)!=3;j++){
 #ifdef GPU_TILES
 int i=(Untiled || overflow)?j:readIndex(offset+j);
 #else
 int i=Untiled?j:readIndex(offset+j);
 #endif
 if(Untiled)i=j;
 vec4 color=texelFetch(Lights,ivec2(0,i),0);
 vec4 pos=texelFetch(Lights,ivec2(1,i),0);
 vec4 direction=texelFetch(Lights,ivec2(2,i),0);
 vec4 lightDir; vec3 lightVec;
 lightComputeDir(position,color.w,pos,lightDir,lightVec);
 float attenuation=lightDir.w;
 if(color.w>1.5) attenuation*=computeSpotFalloff(direction,lightVec);
 // Avoid evaluating BRDFs for lights outside this pixel's influence. Legacy
 // color ramps and environment reflection can contribute even at zero falloff;
 // custom shading models retain control of their own attenuation semantics.
 if(attenuation<=0.0 && (int(base.a)==2 ||
    (int(base.a)==1 && flags==0 && all(equal(reflection.rgb,vec3(0.0)))))) continue;
 if(int(base.a)==1){
 vec2 factors=computeLighting(normal,viewDir,lightDir.xyz,attenuation,n.w);
 if((flags&1)!=0){
    vec3 diffuseRamp=texture(ColorRamp,vec2(factors.x,0.0)).rgb;
    vec3 specularRamp=texture(ColorRamp,vec2(factors.y,0.0)).rgb;
    vec3 specularTerm=(flags&2)!=0 ? spec*factors.y+reflection.rgb : spec;
    result+=(base.rgb*diffuseRamp+specularTerm*specularRamp)*color.rgb;
 }else result+=(base.rgb*factors.x+spec*factors.y+reflection.rgb)*color.rgb;
 }else if(int(base.a)==2){
 vec3 diffuse,specular;
 PBR_ComputeDirectLight(normal,lightDir.xyz,viewDir,color.rgb,spec,n.w,max(dot(normal,viewDir),0.0),diffuse,specular);
 float surfaceFactor=(flags&1)!=0 ? reflection.r*(attenuation==1.0?reflection.g:1.0) : 1.0;
 result+=(base.rgb*diffuse+specular)*attenuation*surfaceFactor;
 }
 // CUSTOM_MODELS
 }
 outFragColor=vec4(result,extra.a);
 gl_FragDepth=depth;
}
