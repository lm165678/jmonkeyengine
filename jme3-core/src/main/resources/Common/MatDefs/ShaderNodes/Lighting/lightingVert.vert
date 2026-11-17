void main(){
    projPosition = worldViewProjectionMatrix * vec4(modelPosition, 1.0);
    viewPosition = (worldViewMatrix * vec4(modelPosition, 1.0)).xyz;
    viewNormal = normalize(normalMatrix * modelNormal);
    viewDir = -viewPosition;
}
