#import "Common/ShaderLib/GLSLCompat.glsllib"
varying vec2 texCoord;
uniform float m_Saturation;
uniform float m_Contrast;
uniform vec3 m_Gain;
uniform vec3 m_Gamma;
uniform vec3 m_Offset;
uniform vec3 m_ShadowGain;
uniform vec3 m_MidtoneGain;
uniform vec3 m_HighlightGain;
uniform vec4 m_Film;
uniform float m_WhiteClip;
uniform float m_Temperature;
uniform float m_Tint;
uniform float m_BlueCorrection;
uniform float m_ExpandGamut;
#ifdef USER_LUT
uniform sampler2D m_UserLut;
#endif
uniform float m_UserLutSize;
uniform float m_LutWeight;
// Matrices use column-major GLSL storage. Scene input is linear Rec.709 D65.
const mat3 RGB_XYZ = mat3(.4124564,.2126729,.0193339, .3575761,.7151522,.1191920, .1804375,.072175,.9503041);
const mat3 XYZ_RGB = mat3(3.24097,-.969244,.05563, -1.537383,1.875968,-.203977, -.498611,.041555,1.056972);
const mat3 D65_D60 = mat3(1.01303,.00769823,-.00284131, .00610531,.998165,.00468516, -.014971,-.00503203,.924507);
const mat3 D60_D65 = mat3(.987224,-.00759836,.00307257, -.00611327,1.00186,-.00509595, .0159533,.00533002,1.08168);
const mat3 XYZ_AP1 = mat3(1.64102338,-.66366286,.01172189, -.32480329,1.61533159,-.00828444, -.2364247,.01675635,.98839486);
const mat3 AP1_XYZ = mat3(.66245418,.27222872,-.00557465, .13400421,.67408177,.00406073, .15618769,.05368952,1.0103391);
const mat3 AP1_AP0 = mat3(.69545224,.04479456,-.00552588, .1406787,.85967112,.00402521, .16386906,.09553432,1.00150067);
const mat3 AP0_AP1 = mat3(1.45143932,-.07655377,.00831615, -.23651075,1.1762297,-.00603245, -.21492857,-.09967593,.9977163);
const vec3 Y_AP1 = vec3(.27222872,.67408177,.05368952);
const mat3 BRADFORD = mat3(.8951,-.7502,.0389, .2664,1.7135,-.0685, -.1614,.0367,1.0296);
const mat3 BRADFORD_INV = mat3(.9869929,.4323053,-.0085287, -.1470543,.5183603,.0400428, .1599627,.0492912,.9684867);
vec2 illuminant(float t) {
    float r = 1000.0 / t;
    float x = t < 4000.0 ? -.2661239*r*r*r-.234358*r*r+.8776956*r+.17991
            : (t <= 7000.0 ? -4.607*r*r*r+2.9678*r*r+.09911*r+.244063
                    : -2.0064*r*r*r+1.9018*r*r+.24748*r+.23704);
    float y = t < 4000.0 ? -1.1063814*x*x*x-1.3481102*x*x+2.18555832*x-.20219683
            : -3.0*x*x+2.87*x-.275;
    return vec2(x,y);
}
vec3 whiteBalance(vec3 c) {
    // Bradford adaptation. Tint uses a small chromaticity offset; not UE's isotherm model.
    vec2 xy = illuminant(m_Temperature) + vec2(0.0, m_Tint * .05);
    vec2 neutral = illuminant(6500.0);
    vec3 source = BRADFORD * vec3(xy.x/xy.y,1.0,(1.0-xy.x-xy.y)/xy.y);
    vec3 target = BRADFORD * vec3(neutral.x/neutral.y,1.0,(1.0-neutral.x-neutral.y)/neutral.y);
    return XYZ_RGB * BRADFORD_INV * ((BRADFORD * RGB_XYZ * c) * target / source);
}
float filmChannel(float v) {
    float slope = m_Film.x;
    float toe = m_Film.y;
    float shoulder = m_Film.z;
    float black = m_Film.w;
    float lo = 1.0 + black - toe;
    float hi = 1.0 + m_WhiteClip - shoulder;
    float anchor = log(.18) / log(10.0);
    float toeAt;
    if (toe > .8) {
        toeAt = (1.0-toe-.18)/slope + anchor;
    } else {
        float q = (.18+black)/lo-1.0;
        toeAt = anchor - .5*log((1.0+q)/(1.0-q))*lo/slope;
    }
    float linearOffset = (1.0-toe)/slope-toeAt;
    float shoulderAt = shoulder/slope-linearOffset;
    float x = log(max(v,1e-10))/log(10.0);
    float middle = slope*(x+linearOffset);
    float dark = x < toeAt ? -black+2.0*lo/(1.0+exp(clamp(-2.0*slope*(x-toeAt)/lo,-80.0,80.0))) : middle;
    float bright = x > shoulderAt ? 1.0+m_WhiteClip-2.0*hi/(1.0+exp(clamp(2.0*slope*(x-shoulderAt)/hi,-80.0,80.0))) : middle;
    float span = shoulderAt-toeAt;
    float f = abs(span) < 1e-6 ? step(toeAt,x) : clamp((x-toeAt)/span,0.0,1.0);
    if (span < 0.0) {
        f = 1.0-f;
    }
    return mix(dark,bright,f*f*(3.0-2.0*f));
}
vec3 film(vec3 ap1) {
    vec3 c = AP1_AP0 * ap1;
    float largest = max(max(c.r,c.g),c.b);
    float smallest = min(min(c.r,c.g),c.b);
    float sat = (max(largest,1e-10)-max(smallest,1e-10))/max(largest,1e-2);
    float chroma = sqrt(max(0.0,dot(c,c)-c.r*c.g-c.g*c.b-c.b*c.r));
    float yc = (c.r+c.g+c.b+1.75*chroma)/3.0;
    float z = (sat-.4)/.2;
    float shaper = .5*(1.0+sign(z)*(1.0-pow(max(1.0-abs(z)*.5,0.0),2.0)));
    float glow = yc <= .08*2.0/3.0 ? 1.0 : (yc >= .16 ? 0.0 : .08/max(yc,1e-8)-.5);
    c *= 1.0+.05*shaper*glow;
    float hue = degrees(atan(sqrt(3.0)*(c.g-c.b),2.0*c.r-c.g-c.b));
    float red = smoothstep(0.0,1.0,1.0-abs(2.0*hue/135.0));
    c.r += red*red*sat*(.03-c.r)*.18;
    c = max(AP0_AP1*c,vec3(0.0));
    c = mix(vec3(dot(c,Y_AP1)),c,.96);
    c = vec3(filmChannel(c.r),filmChannel(c.g),filmChannel(c.b));
    return max(mix(vec3(dot(c,Y_AP1)),c,.93),vec3(0.0));
}
#ifdef USER_LUT
vec3 userGrade(vec3 c) {
    float n = m_UserLutSize;
    vec3 p = clamp(c,0.0,1.0)*(n-1.0);
    float b = floor(p.b);
    vec2 uv = vec2((p.r+.5+b*n)/(n*n),(p.g+.5)/n);
    vec3 a = texture2D(m_UserLut,uv).rgb;
    uv.x = (p.r+.5+min(b+1.0,n-1.0)*n)/(n*n);
    return mix(a,texture2D(m_UserLut,uv).rgb,fract(p.b));
}
#endif
void main() {
    vec2 pixel = floor(texCoord * vec2(1024.0,32.0));
    vec3 code = vec3(mod(pixel.x,32.0),pixel.y,floor(pixel.x/32.0))/31.0;
    float black = .18*exp2(-6.5);
    vec3 c = max(.18*exp2(code*14.0-6.5)-black,vec3(0.0));
    c = XYZ_AP1*D65_D60*RGB_XYZ*whiteBalance(c);
    float luma = max(dot(c,Y_AP1),0.0);
    vec3 chroma = c / max(luma,1e-6) - 1.0;
    float expand = (1.0-exp2(-4.0*dot(chroma,chroma)))
            * (1.0-exp2(-4.0*m_ExpandGamut*luma*luma));
    const mat3 WIDE_XYZ = mat3(.5441691,.2394656,-.0023439, .2395926,.7021530,.0361834, .1666943,.0583814,1.0552183);
    c = mix(c,XYZ_AP1*WIDE_XYZ*XYZ_RGB*D60_D65*AP1_XYZ*c,expand);
    c = max(mix(vec3(luma),c,m_Saturation),vec3(0.0));
    c = .18*pow(c/.18,vec3(m_Contrast));
    c = pow(c,1.0/m_Gamma)*m_Gain+m_Offset;
    float shadows = 1.0-smoothstep(0.0,.09,luma);
    float highlights = smoothstep(.5,1.0,luma);
    c *= shadows*m_ShadowGain + highlights*m_HighlightGain
            + (1.0-shadows-highlights)*m_MidtoneGain;
    const mat3 blue = mat3(.94043727,.0083787,.00054713, -.01830688,.82865999,-.00088337, .07786961,.16296131,1.00033625);
    const mat3 unblue = mat3(1.06318,-.0106337,-.000590887, .0233956,1.20632,.00105248, -.0865726,-.19569,.999538);
    c = mix(c,AP0_AP1*blue*AP1_AP0*c,m_BlueCorrection);
    c = film(c);
    c = mix(c,AP0_AP1*unblue*AP1_AP0*c,m_BlueCorrection);
    c = clamp(XYZ_RGB*D60_D65*AP1_XYZ*c,0.0,1.0);
    #ifdef USER_LUT
    if (m_LutWeight > 0.0) {
        // Artist LUT stores display-referred sRGB values as raw data, never auto-decoded.
        vec3 encoded = mix(12.92*c,1.055*pow(c,vec3(1.0/2.4))-.055,step(vec3(.0031308),c));
        encoded = mix(encoded,userGrade(encoded),m_LutWeight);
        c = mix(encoded/12.92,pow((encoded+.055)/1.055,vec3(2.4)),step(vec3(.04045),encoded));
    }
    #endif
    // Cache linear SDR. The presentation framebuffer encodes sRGB exactly once.
    gl_FragColor = vec4(c,1.0);
}
