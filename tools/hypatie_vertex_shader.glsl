#version 320 es

layout(location = 0) in vec2 vertexIn;

out vec4 fragPosition;

void main() {
    fragPosition = vec4(vertexIn.x , vertexIn.y , 0.0, 1.0);
    gl_Position = fragPosition;
}
