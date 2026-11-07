package com.mycompany.deadsignal;

import java.awt.Component;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public final class IntroVideoCheck {
    private static int checks;
    private static final Font MENU_FONT = new Font("Monospaced", Font.BOLD, 15);

    static final class FakePlayback implements IntroVideoPanel.Playback {
        final JPanel view = new JPanel();
        Runnable ended;
        Runnable started;
        Consumer<Throwable> failed;
        Runnable afterClose;
        int plays;
        int closes;
        boolean deferClose;

        public JComponent component() { return view; }

        public void play(Runnable started, Runnable ended, Consumer<Throwable> failed) {
            this.started = started;
            this.ended = ended;
            this.failed = failed;
            plays++;
        }

        public void close(Runnable closed) {
            closes++;
            afterClose = closed;
            if (!deferClose) closed.run();
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            skipBeforePlayback();
            endedAndSkipAreOneTransition();
            cleanupFinishesBeforeGameplay();
            missingAndFailedPlaybackContinue();
            detachedIntroCannotStartGameplay();
            fontAndBottomRightLayoutMatchMenu();
            newGameReplaysButRestartDoesNot();
            loadingScreenIsBlack();
            require(IntroVideoPanel.findSource() != null, "intro is bundled with the build");
        });
        callbacksReturnToSwing();
        System.out.println("Intro video checks passed: " + checks);
    }

    private static IntroVideoPanel panel(FakePlayback playback, Runnable begin) {
        return new IntroVideoPanel("file:/intro.mp4", MENU_FONT, begin, source -> playback);
    }

    private static void skipBeforePlayback() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger games = new AtomicInteger();
        IntroVideoPanel intro = new IntroVideoPanel("file:/intro.mp4", MENU_FONT,
                games::incrementAndGet, source -> {
                    created.incrementAndGet();
                    return new FakePlayback();
                });
        require(intro.skipButton.isVisible() && intro.skipButton.isEnabled(),
                "Skip is available before the video loads");
        intro.skipButton.doClick(0);
        intro.startPlayback();
        require(games.get() == 1 && created.get() == 0, "early Skip never loads a late player");
        require(!intro.loadingTimeout.isRunning(), "early Skip has no pending load timeout");
    }

    private static void endedAndSkipAreOneTransition() {
        for (boolean skipFirst : new boolean[] {true, false}) {
            FakePlayback playback = new FakePlayback();
            AtomicInteger games = new AtomicInteger();
            IntroVideoPanel intro = panel(playback, games::incrementAndGet);
            intro.startPlayback();
            intro.startPlayback();
            require(playback.plays == 1, "an intro starts playback only once");
            playback.started.run();
            require(!intro.loadingTimeout.isRunning(), "playing cancels the load timeout");
            if (skipFirst) intro.skipButton.doClick(0);
            playback.ended.run();
            intro.skipButton.doClick(0);
            playback.failed.accept(new IllegalStateException("late callback"));
            require(games.get() == 1, "Skip and natural end cannot create two games");
            require(playback.closes == 1, "player is released once");
            require(!intro.skipButton.isEnabled(), "Skip is disabled during the transition");
        }
    }

    private static void cleanupFinishesBeforeGameplay() {
        FakePlayback playback = new FakePlayback();
        playback.deferClose = true;
        AtomicInteger games = new AtomicInteger();
        IntroVideoPanel intro = panel(playback, games::incrementAndGet);
        intro.startPlayback();
        intro.skipButton.doClick(0);
        require(games.get() == 0, "gameplay waits until audio/video resources are released");
        playback.afterClose.run();
        playback.afterClose.run();
        require(games.get() == 1, "cleanup completion can start only one game");
    }

    private static void missingAndFailedPlaybackContinue() {
        AtomicInteger missing = new AtomicInteger();
        IntroVideoPanel absent = new IntroVideoPanel(null, MENU_FONT, missing::incrementAndGet,
                source -> { throw new AssertionError("missing files must not load a player"); });
        absent.startPlayback();
        require(missing.get() == 1, "missing video falls through to gameplay");

        for (int failure = 0; failure < 4; failure++) {
            final int mode = failure;
            AtomicInteger games = new AtomicInteger();
            FakePlayback playback = new FakePlayback();
            IntroVideoPanel intro = new IntroVideoPanel("file:/intro.mp4", MENU_FONT,
                    games::incrementAndGet, source -> {
                if (mode == 0) throw new IllegalStateException("player startup failed");
                if (mode == 1) throw new UnsatisfiedLinkError("native decoder missing");
                return playback;
            });
            intro.startPlayback();
            if (mode == 2) playback.failed.accept(new IllegalStateException("decode error"));
            if (mode == 3) intro.loadingTimeout.getActionListeners()[0].actionPerformed(
                    new ActionEvent(intro, ActionEvent.ACTION_PERFORMED, "timeout"));
            require(games.get() == 1, "playback failure does not leave a black screen");
            require(!intro.loadingTimeout.isRunning(), "failed playback cancels its timeout");
            if (mode >= 2) require(playback.closes == 1, "failed player is released");
        }
    }

    private static void detachedIntroCannotStartGameplay() {
        FakePlayback playback = new FakePlayback();
        playback.deferClose = true;
        AtomicInteger games = new AtomicInteger();
        IntroVideoPanel intro = panel(playback, games::incrementAndGet);
        intro.startPlayback();
        intro.skipButton.doClick(0);
        intro.removeNotify();
        playback.afterClose.run();
        playback.ended.run();
        require(games.get() == 0, "a removed intro cannot replace a later screen");
        require(playback.closes == 1, "removal does not release the same player twice");

        FakePlayback unused = new FakePlayback();
        IntroVideoPanel removed = panel(unused, games::incrementAndGet);
        removed.removeNotify();
        removed.startPlayback();
        require(unused.plays == 0 && games.get() == 0, "removed intros never start loading");

        FakePlayback active = new FakePlayback();
        IntroVideoPanel canceled = panel(active, games::incrementAndGet);
        canceled.startPlayback();
        canceled.removeNotify();
        require(active.closes == 1 && games.get() == 0, "removal cancels active playback");
        require(!canceled.loadingTimeout.isRunning(), "removal cancels the load timeout");
    }

    private static void fontAndBottomRightLayoutMatchMenu() {
        Main.MainMenuPanel menu = new Main.MainMenuPanel(null);
        JButton newGame = button(menu, "NEW GAME");
        require(newGame != null, "main menu New Game button exists");
        for (int[] size : new int[][] {{840,688}, {1000,700}, {1920,1080}, {2560,1080}, {420,344}}) {
            menu.setSize(size[0], size[1]);
            menu.doLayout();
            FakePlayback playback = new FakePlayback();
            IntroVideoPanel intro = new IntroVideoPanel("file:/intro.mp4", newGame.getFont(),
                    () -> { }, source -> playback);
            intro.setSize(size[0], size[1]);
            intro.startPlayback();
            intro.doLayout();
            require(intro.skipButton.getFont().equals(newGame.getFont()),
                    "Skip uses the same menu font, weight, and responsive size");
            double scale = Math.max(.80, Math.min(1.60,
                    Math.min(size[0] / 840.0, size[1] / 688.0)));
            int margin = (int) Math.round(24 * scale);
            Rectangle bounds = intro.skipButton.getBounds();
            require(size[0] - bounds.x - bounds.width == margin, "Skip stays inset from the right");
            require(size[1] - bounds.y - bounds.height == margin, "Skip stays inset from the bottom");
            require(intro.getLayer(intro.skipButton) > intro.getLayer(playback.view),
                    "Skip stays above the video");
            require(playback.view.getBounds().equals(new Rectangle(0, 0, size[0], size[1])),
                    "video view fills the content area");
            intro.removeNotify();
        }
    }

    private static void newGameReplaysButRestartDoesNot() {
        AtomicInteger plays = new AtomicInteger();
        AtomicReference<Main> current = new AtomicReference<>();
        for (int gameNumber = 0; gameNumber < 2; gameNumber++) {
            FakePlayback playback = new FakePlayback();
            IntroVideoPanel intro = new IntroVideoPanel("file:/intro.mp4", MENU_FONT,
                    () -> current.set(new Main()), source -> {
                        plays.incrementAndGet();
                        return playback;
                    });
            current.set(null);
            intro.startPlayback();
            require(current.get() == null, "gameplay is not created underneath the intro");
            playback.ended.run();
            Main game = current.get();
            require(game != null, "finishing the intro creates gameplay");
            require(!game.gameTimer.isRunning() && !game.movementTimer.isRunning(),
                    "game timers wait for the gameplay panel to attach");
            Object restart = game.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(javax.swing.KeyStroke.getKeyStroke("R"));
            game.getActionMap().get(restart).actionPerformed(
                    new ActionEvent(game, ActionEvent.ACTION_PERFORMED, "restart"));
            game.restartGame();
            require(plays.get() == gameNumber + 1, "R and direct restart do not replay the intro");
        }
        require(plays.get() == 2, "every separate New Game gets its own intro");
    }

    private static void loadingScreenIsBlack() {
        IntroVideoPanel intro = panel(new FakePlayback(), () -> { });
        intro.setSize(840, 688);
        intro.doLayout();
        BufferedImage image = new BufferedImage(840, 688, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.MAGENTA);
        graphics.fillRect(0, 0, 840, 688);
        intro.paint(graphics);
        graphics.dispose();
        require(image.getRGB(420, 344) == Color.BLACK.getRGB(),
                "loading screen paints black instead of leaving the old menu visible");
    }

    private static void callbacksReturnToSwing() throws Exception {
        AtomicReference<FakePlayback> player = new AtomicReference<>();
        AtomicInteger games = new AtomicInteger();
        AtomicReference<Boolean> swingThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FakePlayback playback = new FakePlayback();
            player.set(playback);
            IntroVideoPanel intro = panel(playback, () -> {
                swingThread.set(SwingUtilities.isEventDispatchThread());
                games.incrementAndGet();
            });
            intro.startPlayback();
        });
        Thread callback = new Thread(() -> player.get().ended.run(), "media-end-test");
        callback.start();
        callback.join(2000);
        require(!callback.isAlive(), "media completion does not block its own thread");
        SwingUtilities.invokeAndWait(() -> {
            require(games.get() == 1 && Boolean.TRUE.equals(swingThread.get()),
                    "media callbacks return to Swing before creating gameplay");
        });
    }

    private static JButton button(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton candidate && candidate.getText().equals(text)) return candidate;
            if (child instanceof Container container) {
                JButton found = button(container, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
