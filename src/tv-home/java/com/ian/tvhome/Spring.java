package com.ian.tvhome;

/**
 * A critically-damped-by-default spring described the way Apple describes them:
 * "response" (seconds to reach the target, roughly) and damping ratio (1 = no overshoot).
 * Changing the target mid-flight keeps the current value and velocity, so motion is
 * always interruptible and never jumps.
 */
final class Spring {
    float value, velocity, target;
    private final float stiffness, damping;

    Spring(float response, float dampingRatio, float initial) {
        float w = (float) (2 * Math.PI / response);
        stiffness = w * w;
        damping = (float) (4 * Math.PI * dampingRatio / response);
        value = target = initial;
    }

    void to(float t) { target = t; }

    void snap(float v) { value = target = v; velocity = 0; }

    /** Adds a push in the current direction, e.g. a nudge or a bump at an edge. */
    void kick(float v) { velocity += v; }

    boolean moving() {
        return Math.abs(velocity) > 0.01f || Math.abs(target - value) > 0.001f;
    }

    /** Advances by dt seconds; returns true while still moving. */
    boolean step(float dt) {
        if (!moving()) { value = target; velocity = 0; return false; }
        int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float a = -stiffness * (value - target) - damping * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        return true;
    }
}
