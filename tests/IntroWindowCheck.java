package com.mycompany.deadsignal;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.layout.StackPane;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

/** Exercises the real menu, embedded player, Skip, and R in an off-screen window. */
public final class IntroWindowCheck {
    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame("Intro integration check");
                frame.setBounds(-20000, -20000, 1000, 700);
                frame.setContentPane(new Main.MainMenuPanel(frame));
                frame.setVisible(true);
                button(frame.getContentPane(), "NEW GAME").doClick(0);
            });
            for (int gameNumber = 1; gameNumber <= 2; gameNumber++) {
                awaitEmbeddedVideo();
                SwingUtilities.invokeAndWait(() -> {
                    IntroVideoPanel intro = (IntroVideoPanel) frame.getContentPane();
                    if (!intro.skipButton.isShowing()) throw new AssertionError("Skip is not visible");
                    intro.skipButton.doClick(0);
                });
                awaitGameplay();
                SwingUtilities.invokeAndWait(() -> {
                    Main game = (Main) frame.getContentPane();
                    Object restart = game.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke("R"));
                    game.getActionMap().get(restart).actionPerformed(
                            new ActionEvent(game, ActionEvent.ACTION_PERFORMED, "restart"));
                    if (frame.getContentPane() != game) throw new AssertionError("R replayed the intro");
                });
                System.out.println("Actual window check " + gameNumber + ": video playing, Skip starts gameplay, R stays in gameplay.");
                if (gameNumber < 2) {
                    SwingUtilities.invokeAndWait(() -> {
                        Main game = (Main) frame.getContentPane();
                        Main.GameSession session = game.ownerSession;
                        if (session == null || session.run != game) {
                            throw new AssertionError("Intro lost the owning game session");
                        }
                        session.showMenu();
                        Container menu = frame.getContentPane();
                        answerConfirmation(JOptionPane.NO_OPTION,
                                () -> button(menu, "NEW GAME").doClick(0));
                        if (frame.getContentPane() != menu || session.run != game) {
                            throw new AssertionError("Canceling New Game replaced the current run");
                        }
                        button(menu, "CONTINUE").doClick(0);
                        if (frame.getContentPane() != game) {
                            throw new AssertionError("Continue replayed the intro instead of resuming");
                        }
                        session.showMenu();
                        answerConfirmation(JOptionPane.YES_OPTION,
                                () -> button(frame.getContentPane(), "NEW GAME").doClick(0));
                    });
                    System.out.println("Session check: canceled New Game keeps the run, Continue bypasses the intro, confirmed New Game replays it.");
                }
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame != null) frame.dispose(); });
            try { Platform.exit(); } catch (IllegalStateException ignored) { }
        }
    }

    private static void awaitEmbeddedVideo() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            AtomicReference<JFXPanel> surface = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                if (!(frame.getContentPane() instanceof IntroVideoPanel intro)) {
                    throw new AssertionError("New Game skipped the intro unexpectedly");
                }
                for (Component child : intro.getComponents()) {
                    if (child instanceof JFXPanel panel) surface.set(panel);
                }
            });
            if (surface.get() != null) {
                CompletableFuture<Boolean> playing = new CompletableFuture<>();
                Platform.runLater(() -> {
                    try {
                        if (surface.get().getScene() == null) {
                            playing.complete(false);
                            return;
                        }
                        StackPane root = (StackPane) surface.get().getScene().getRoot();
                        MediaView view = (MediaView) root.getChildren().get(0);
                        MediaPlayer player = view.getMediaPlayer();
                        player.setMute(true);
                        playing.complete(player.getStatus() == MediaPlayer.Status.PLAYING
                                && player.getCurrentTime().toMillis() >= 150
                                && view.getFitWidth() > 0 && view.getFitHeight() > 0);
                    } catch (Throwable failure) {
                        playing.completeExceptionally(failure);
                    }
                });
                if (playing.get(3, TimeUnit.SECONDS)) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Embedded video did not begin playing");
    }

    private static void awaitGameplay() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> gameplay = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> gameplay.set(frame.getContentPane() instanceof Main));
            if (gameplay.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Skip did not start gameplay");
    }

    private static JButton button(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton found && found.getText().equals(text)) return found;
            if (child instanceof Container nested) {
                JButton found = button(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void answerConfirmation(int answer, Runnable action) {
        Timer choice = new Timer(50, e -> {
            for (Window window : frame.getOwnedWindows()) {
                if (window.isShowing()) {
                    JOptionPane pane = optionPane(window);
                    if (pane != null) {
                        ((Timer) e.getSource()).stop();
                        pane.setValue(answer);
                        return;
                    }
                }
            }
        });
        choice.start();
        try { action.run(); } finally { choice.stop(); }
    }

    private static JOptionPane optionPane(Container parent) {
        if (parent instanceof JOptionPane pane) return pane;
        for (Component child : parent.getComponents()) {
            if (child instanceof Container nested) {
                JOptionPane found = optionPane(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
