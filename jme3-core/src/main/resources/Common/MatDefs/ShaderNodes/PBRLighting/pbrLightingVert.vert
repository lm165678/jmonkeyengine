void main(){
    projPosition = worldViewProjectionMatrix * vec4(modelPosition, 1.0);
    worldPosition = (worldMatrix * vec4(modelPosition, 1.0)).xyz;
    worldNormal = normalize(worldNormalMatrix * modelNormal);
    viewDir = cameraPosition - worldPosition;
}
