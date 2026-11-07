package com.mycompany.deadsignal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.VideoTrack;
import javafx.util.Duration;

/** Checks native MP4 decoding and replay, muted and without showing a video window. */
public final class IntroMediaCheck {
    private static final CountDownLatch done = new CountDownLatch(1);
    private static final AtomicReference<Throwable> failure = new AtomicReference<>();
    private static MediaPlayer active;
    private static int completed;

    public static void main(String[] args) throws Exception {
        String source = IntroVideoPanel.findSource();
        if (source == null) throw new AssertionError("Bundled intro is missing");
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            play(source);
        });
        boolean finished = done.await(25, TimeUnit.SECONDS);
        Platform.runLater(() -> {
            if (active != null) active.dispose();
            Platform.exit();
        });
        if (!finished) throw new AssertionError("Native intro playback timed out");
        if (failure.get() != null) throw new AssertionError("Native intro playback failed", failure.get());
        System.out.println("Native intro checks passed: decoded, reached end, disposed, and replayed twice.");
    }

    private static void play(String source) {
        try {
            Media media = new Media(source);
            media.setOnError(() -> fail(media.getError()));
            MediaPlayer player = new MediaPlayer(media);
            active = player;
            player.setMute(true);
            player.setOnError(() -> fail(player.getError()));
            player.setOnReady(() -> {
                if (media.getTracks().stream().noneMatch(track -> track instanceof VideoTrack)
                        || media.getWidth() <= 0 || media.getHeight() <= 0) {
                    fail(new AssertionError("Intro has no decodable video track"));
                } else {
                    System.out.println("Intro: " + media.getWidth() + "x" + media.getHeight()
                            + ", " + media.getDuration().toSeconds() + " seconds");
                }
            });
            player.setOnPlaying(() -> {
                PauseTransition firstFrames = new PauseTransition(Duration.millis(500));
                firstFrames.setOnFinished(e -> player.seek(media.getDuration().subtract(Duration.millis(300))));
                firstFrames.play();
            });
            player.setOnEndOfMedia(() -> {
                player.dispose();
                active = null;
                if (player.getStatus() != MediaPlayer.Status.DISPOSED) {
                    fail(new AssertionError("MediaPlayer was not released"));
                } else if (++completed < 2) {
                    play(source);
                } else {
                    done.countDown();
                }
            });
            player.play();
        } catch (Throwable problem) {
            fail(problem);
        }
    }

    private static void fail(Throwable problem) {
        failure.compareAndSet(null, problem);
        done.countDown();
    }
}
