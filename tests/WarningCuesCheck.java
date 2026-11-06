package com.mycompany.deadsignal;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import javax.swing.SwingUtilities;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

public class WarningCuesCheck {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static byte[] samples(Main.CueSynth synth, Main.CueMix mix, double seconds) {
        byte[] output = new byte[(int) (seconds * Main.CUE_SAMPLE_RATE) * 2];
        synth.render(output, mix);
        return output;
    }
    static double rms(byte[] bytes) {
        double sum = 0;
        for (int i = 0; i < bytes.length; i += 2) {
            int sample = (short) ((bytes[i] & 255) | bytes[i + 1] << 8);
            require(Math.abs(sample) < 29492, "Clipped output");
            sum += (sample / 32768.0) * (sample / 32768.0);
        }
        return Math.sqrt(sum / (bytes.length / 2));
    }
    static BufferedImage overlay(Main.WarningCues cues) {
        BufferedImage image = new BufferedImage(840, 688, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(55, 60, 65));
        g.fillRect(0, 0, 840, 688);
        cues.draw(g, 840, 588);
        g.dispose();
        return image;
    }
    static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
    static void press(Main game, int code, int modifiers) {
        Object action = game.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(code, modifiers, false));
        require(action != null, "Missing movement binding");
        game.getActionMap().get(action).actionPerformed(
                new ActionEvent(game, ActionEvent.ACTION_PERFORMED, String.valueOf(action)));
    }
    static void checkSettingsMovement() {
        long[] clock = {1_000_000_000L};
        Main game = new Main(() -> clock[0]);
        for (char[] row : game.map) Arrays.fill(row, '.');
        game.playerX = 5;
        game.playerY = 5;
        game.monsterX = Main.COLS - 2;
        game.monsterY = Main.ROWS - 2;
        press(game, KeyEvent.VK_W, InputEvent.SHIFT_DOWN_MASK);
        require(game.playerX == 5 && game.playerY == 4, "Movement setup failed");
        game.movementTimer.start();
        game.gameTimer.start();
        Main.WarningPause pause = game.pauseWarningSettings();
        require(!game.movementTimer.isRunning() && !game.gameTimer.isRunning(),
                "Settings leave a gameplay timer running");
        require(game.heldMovementKeys.isEmpty() && !game.runningHeld,
                "Settings retain held movement");
        clock[0] += 2_000_000_000L;
        press(game, KeyEvent.VK_D, 0);
        game.updatePlayerMovement();
        require(game.playerX == 5 && game.playerY == 4 && game.heldMovementKeys.isEmpty(),
                "Player moves or queues input while settings are open");
        game.resumeWarningSettings(pause);
        game.updatePlayerMovement();
        require(game.playerX == 5 && game.playerY == 4, "Held movement resumes after settings");
        press(game, KeyEvent.VK_D, 0);
        require(game.playerX == 5 && game.playerY == 4, "Settings bypass movement cooldown");
        clock[0] += 250_000_000L;
        game.updatePlayerMovement();
        require(game.playerX == 6 && game.playerY == 4, "Fresh movement fails after settings");
        game.removeNotify();
        System.out.println("PASS warning settings pause both timers, clear input, and preserve movement timing");
    }
    static void verifyGraphics(File directory) throws Exception {
        checkSettingsMovement();
        Main.WarningCues cues = new Main.WarningCues();
        cues.update(0, 1, true, 10000);
        BufferedImage quiet = overlay(cues);
        cues.update(0.5, 1, true, 11000);
        BufferedImage alert = overlay(cues);
        cues.update(1, 1, true, 12000);
        cues.beatPhase = 0.06;
        BufferedImage hunted = overlay(cues);
        int base = quiet.getRGB(420, 300);
        require(quiet.getRGB(0, 200) == base, "Proximity alone causes an awareness overlay");
        require(alert.getRGB(0, 200) != quiet.getRGB(0, 200), "Alert cue missing");
        require(hunted.getRGB(0, 200) != alert.getRGB(0, 200), "Hunted cue not stronger");
        for (BufferedImage image : new BufferedImage[] {alert, hunted}) {
            require(image.getRGB(420, 300) == base, "Playfield center obscured");
            require(image.getRGB(10, 600) == base, "HUD obscured");
        }
        Main.WARNING_SETTINGS.reducedEffects = true;
        BufferedImage reduced = overlay(cues);
        cues.beatPhase = 0.4;
        require(Arrays.equals(pixels(reduced), pixels(overlay(cues))),
                "Reduced effects still pulse");
        Main.WARNING_SETTINGS.reducedEffects = false;
        cues.update(1, 1, false, 13000);
        require(Arrays.equals(pixels(quiet), pixels(overlay(cues))), "Inactive overlay remains");
        System.out.println("PASS awareness edges, clear center/HUD, reduced effects, inactive cleanup");

        Main game = new Main();
        require(!game.gameTimer.isRunning(), "Detached game timer is running");
        int noiseEvents = game.soundQueue.size();
        game.monsterX = game.playerX + 1;
        game.monsterY = game.playerY;
        game.monsterState = Main.MonsterState.PATROLLING;
        double nearby = game.nearbyMonsterLevel();
        require(nearby > 0 && game.computeAwarenessTarget() == 0,
                "Unaware nearby monster has no proximity cue");
        game.hidden = true;
        require(game.nearbyMonsterLevel() > 0 && game.nearbyMonsterLevel() < nearby,
                "Locker proximity muffling missing");
        game.hidden = false;
        game.monsterX = game.playerX + 2;
        double openHall = game.nearbyMonsterLevel();
        char floor = game.map[game.playerY][game.playerX + 1];
        game.map[game.playerY][game.playerX + 1] = '#';
        require(game.nearbyMonsterLevel() > 0 && game.nearbyMonsterLevel() < openHall,
                "Walls do not muffle proximity audio");
        game.map[game.playerY][game.playerX + 1] = floor;
        game.monsterX = Main.COLS - 2;
        game.monsterY = Main.ROWS - 2;
        require(game.nearbyMonsterLevel() == 0, "Distant monster produces a proximity cue");
        game.updateWarningCues(System.currentTimeMillis());
        require(game.soundQueue.size() == noiseEvents && game.noise == 0,
                "Warning feedback changes simulated sound events");
        require(!game.warningCues.audio.target.active(), "Detached game produces audio");
        System.out.println("PASS unaware proximity, distance cutoff, wall/locker muffling, AI-noise independence");

        long generation = game.warningCues.audio.target.generation();
        game.warningCues.update(1, 1, true, 20000);
        game.restartGame();
        require(game.warningCues.audio.target.generation() > generation
                && !game.warningCues.audio.target.active(), "Restart retains old cues");
        game.won = true;
        game.warningCues.update(1, 1, true, 21000);
        game.updateGame();
        require(!game.warningCues.audio.target.active(), "Victory retains audio");
        game.won = false;
        game.gameOver = true;
        game.warningCues.update(1, 1, true, 22000);
        game.updateGame();
        require(!game.warningCues.audio.target.active(), "Defeat retains audio");
        game.gameOver = false;
        game.warningSettingsOpen = true;
        game.nextShiftAt = 0;
        int shift = game.shiftNumber;
        game.updateGame();
        require(game.shiftNumber == shift, "Game advances during warning settings");
        game.warningSettingsOpen = false;
        game.restartGame();
        System.out.println("PASS restart, victory/defeat cleanup, settings pause");

        game.setSize(1920, 1080);
        for (double awareness : new double[] {0, 0.2, 0.5, 1}) {
            game.awareness = awareness;
            game.warningCues.update(awareness, 1, true, 30000 + (long) (awareness * 1000));
            game.warningCues.beatPhase = 0.06;
            BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            game.paint(g);
            g.dispose();
            ImageIO.write(image, "png", new File(directory, "awareness-" + (int) (awareness * 100) + ".png"));
        }
    }
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        directory.mkdirs();
        Main.WARNING_SETTINGS.volume = 55;
        Main.WarningCues cues = new Main.WarningCues();
        cues.update(0, 0, true, 10000);
        cues.update(0.75, 0, true, 12000);
        require(cues.sting == 1, "First escalation sting missing");
        cues.update(0.9, 0, true, 13000);
        require(cues.sting == 1, "Repeated escalation sting");
        cues.update(0.5, 0, true, 14000);
        cues.update(0.8, 0, true, 15000);
        require(cues.sting == 1, "Sting cooldown failed");
        cues.update(0.5, 0, true, 22000);
        cues.update(0.8, 0, true, 24000);
        require(cues.sting == 2, "Sting does not rearm");
        System.out.println("PASS escalation transition, cooldown, rearming");

        double[] levels = new double[3];
        Main.CueMix[] mixes = {
                new Main.CueMix(0, 0, 0.55, true, 0, 1),
                new Main.CueMix(0, 1, 0.55, true, 0, 1),
                new Main.CueMix(1, 1, 0.55, true, 0, 1)
        };
        for (int i = 0; i < mixes.length; i++) {
            Main.CueSynth synth = new Main.CueSynth();
            samples(synth, mixes[i], 2);
            levels[i] = rms(samples(synth, mixes[i], 2));
        }
        require(levels[1] > levels[0] * 1.3, "Proximity is inaudible compared with baseline");
        require(levels[2] > levels[1] * 2, "Awareness does not escalate the audio");
        System.out.println("PASS PCM escalation without clipping; RMS=" + Arrays.toString(levels));

        Main.CueSynth synth = new Main.CueSynth();
        samples(synth, mixes[2], 1);
        samples(synth, new Main.CueMix(1, 1, 0, true, 0, 1), 0.4);
        require(rms(samples(synth, new Main.CueMix(1, 1, 0, true, 0, 1), 0.1)) == 0,
                "Mute leaves audible samples");
        samples(synth, mixes[2], 1);
        samples(synth, new Main.CueMix(1, 1, 0.55, false, 1, 1), 0.4);
        require(rms(samples(synth, new Main.CueMix(1, 1, 0.55, false, 1, 1), 0.1)) == 0,
                "Inactive output not silent");
        require(rms(samples(synth, new Main.CueMix(0, 0, 0, false, 1, 2), 0.1)) == 0,
                "Restart leaks old audio");
        System.out.println("PASS mute, focus/inactive fade, generation reset to silence");

        byte[][] segments = new byte[4][];
        Main.CueSynth previewSynth = new Main.CueSynth();
        segments[0] = samples(previewSynth, mixes[0], 2);
        segments[1] = samples(previewSynth, mixes[1], 3);
        segments[2] = samples(previewSynth, new Main.CueMix(0.5, 1, 0.55, true, 0, 1), 3);
        segments[3] = samples(previewSynth, new Main.CueMix(1, 1, 0.55, true, 1, 1), 3);
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        for (byte[] segment : segments) buffer.write(segment);
        byte[] bytes = buffer.toByteArray();
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(bytes),
                Main.WarningAudio.FORMAT, bytes.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, new File(directory, "cue-preview.wav"));
        }
        SwingUtilities.invokeAndWait(() -> {
            try { verifyGraphics(directory); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("ALL CUE INTEGRATION CHECKS PASSED");
    }
}
