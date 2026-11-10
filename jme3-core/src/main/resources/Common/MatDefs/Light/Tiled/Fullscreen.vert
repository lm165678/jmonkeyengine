in vec3 inPosition;
void main(){ gl_Position=vec4(inPosition.xy*2.0-1.0,0.0,1.0); }
