float exposure(float value, float gain) {
    return value * gain;
}

void main() {
    float result = exposure(/*<# value: #>*/0.5, /*<# gain: #>*/2.0);
}
