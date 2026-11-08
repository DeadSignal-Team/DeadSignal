package com.mycompany.deadsignal;

/** Run against the release JAR and its bundled runtime, outside the checkout. */
public final class ReleaseModeCheck {
    public static void main(String[] args) throws Exception {
        if (Main.DEBUG) throw new AssertionError("Debug mode is enabled");
        String origin = Main.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!origin.endsWith("/DeadSignal.jar")) throw new AssertionError("Not running the packaged JAR: " + origin);
        for (String name : new String[] {"intro.mp4", "outro.mp4"}) {
            String source = IntroVideoPanel.findSource(name);
            if (source == null || !source.startsWith("file:")) {
                throw new AssertionError("Video did not load from the bundled files: " + name);
            }
        }
        Main game = new Main();
        try {
            for (int sample = 0; sample < 100; sample++) {
                long delay = game.nextShiftDelay();
                if (delay < 120_000 || delay >= 180_000) {
                    throw new AssertionError("Incorrect normal-mode shift interval: " + delay);
                }
            }
        } finally {
            game.gameTimer.stop();
            game.movementTimer.stop();
        }
        System.out.println("Release check: packaged JAR, bundled cutscenes, debug off, normal shift timing.");
    }
}
