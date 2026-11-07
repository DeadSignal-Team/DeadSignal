package com.mycompany.deadsignal;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

/** The New Game intro. Gameplay is created only after this panel finishes. */
final class IntroVideoPanel extends JLayeredPane {
    interface Playback {
        JComponent component();
        void play(Runnable started, Runnable ended, Consumer<Throwable> failed);
        void close(Runnable closed);
    }

    interface PlaybackFactory {
        Playback create(String source);
    }

    private final String source;
    private final Font menuFont;
    private final Runnable beginGame;
    private final PlaybackFactory playbackFactory;
    final JButton skipButton;
    final Timer loadingTimeout;
    private Playback playback;
    private boolean started;
    private boolean finished;
    private boolean detached;
    private boolean gameStarted;

    static void showBeforeGame(JFrame frame, Font menuFont, Runnable beginGame) {
        IntroVideoPanel intro = new IntroVideoPanel(findSource(), menuFont,
                beginGame, JavaFxPlayback::new);
        frame.setContentPane(intro);
        frame.revalidate();
        frame.repaint();
    }

    static String findSource() {
        URL bundled = IntroVideoPanel.class.getResource("/assets/video/intro.mp4");
        if (bundled != null) {
            return bundled.toExternalForm();
        }
        File local = new File("assets/video/intro.mp4");
        return local.isFile() ? local.toURI().toString() : null;
    }

    IntroVideoPanel(String source, Font menuFont, Runnable beginGame,
                    PlaybackFactory playbackFactory) {
        this.source = source;
        this.menuFont = menuFont;
        this.beginGame = beginGame;
        this.playbackFactory = playbackFactory;
        setOpaque(true);
        setBackground(Color.BLACK);

        skipButton = new JButton("SKIP") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics background = g.create();
                background.setColor(getBackground());
                background.fillRect(0, 0, getWidth(), getHeight());
                background.dispose();
                super.paintComponent(g);
            }
        };
        skipButton.setFont(menuFont);
        skipButton.setForeground(Color.WHITE);
        skipButton.setBackground(new Color(25, 18, 23, 220));
        skipButton.setOpaque(false);
        skipButton.setContentAreaFilled(false);
        skipButton.setFocusPainted(false);
        skipButton.setBorder(BorderFactory.createLineBorder(new Color(170, 25, 38), 2));
        skipButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        skipButton.getAccessibleContext().setAccessibleName("Skip intro video");
        skipButton.addActionListener(e -> finishIntro());
        skipButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                skipButton.setBackground(new Color(115, 18, 30, 235));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                skipButton.setBackground(new Color(25, 18, 23, 220));
            }
        });
        add(skipButton, PALETTE_LAYER);
        loadingTimeout = new Timer(15_000,
                e -> playbackFailed(new IllegalStateException("Video did not start in time")));
        loadingTimeout.setRepeats(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    @Override
    public void doLayout() {
        if (playback != null) {
            playback.component().setBounds(0, 0, getWidth(), getHeight());
        }
        // Use the menu's font family/style and the same responsive size range.
        double scale = Math.max(0.80, Math.min(1.60,
                Math.min(getWidth() / 840.0, getHeight() / 688.0)));
        skipButton.setFont(menuFont.deriveFont((float) Math.round(15 * scale)));
        int margin = (int) Math.round(24 * scale);
        int width = Math.min((int) Math.round(110 * scale), Math.max(1, getWidth() - margin * 2));
        int height = (int) Math.round(44 * scale);
        skipButton.setBounds(Math.max(0, getWidth() - margin - width),
                Math.max(0, getHeight() - margin - height), width, height);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(this::startPlayback);
    }

    void startPlayback() {
        if (started || finished || detached) {
            return;
        }
        started = true;
        if (source == null) {
            playbackFailed(new IllegalStateException("Missing assets/video/intro.mp4"));
            return;
        }
        try {
            playback = playbackFactory.create(source);
            add(playback.component(), DEFAULT_LAYER);
            revalidate();
            doLayout();
            repaint();
            loadingTimeout.start();
            playback.play(() -> onSwing(loadingTimeout::stop),
                    () -> onSwing(this::finishIntro),
                    failure -> onSwing(() -> playbackFailed(failure)));
        } catch (RuntimeException | LinkageError failure) {
            playbackFailed(failure);
        }
    }

    private void playbackFailed(Throwable failure) {
        if (!finished && !detached) {
            System.err.println("Intro video unavailable: " + failure.getMessage());
            finishIntro();
        }
    }

    private void finishIntro() {
        if (finished || detached) {
            return;
        }
        finished = true;
        loadingTimeout.stop();
        skipButton.setEnabled(false);
        closePlayback(() -> {
            if (!detached && !gameStarted) {
                gameStarted = true;
                beginGame.run();
            }
        });
    }

    private void closePlayback(Runnable closed) {
        Playback previous = playback;
        playback = null;
        if (previous == null) {
            closed.run();
            return;
        }
        try {
            previous.close(() -> onSwing(closed));
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("Could not release intro video: " + failure.getMessage());
            closed.run();
        }
    }

    @Override
    public void removeNotify() {
        detached = true;
        finished = true;
        loadingTimeout.stop();
        closePlayback(() -> { });
        super.removeNotify();
    }

    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    static final class JavaFxPlayback implements Playback {
        private final JFXPanel surface = new JFXPanel();
        private final String source;
        private final AtomicBoolean closed = new AtomicBoolean();
        private MediaPlayer player;

        JavaFxPlayback(String source) {
            this.source = source;
            // A later New Game needs the same JavaFX runtime again.
            Platform.setImplicitExit(false);
        }

        @Override
        public JComponent component() {
            return surface;
        }

        @Override
        public void play(Runnable started, Runnable ended, Consumer<Throwable> failed) {
            Platform.runLater(() -> {
                if (closed.get()) {
                    return;
                }
                try {
                    Media media = new Media(source);
                    media.setOnError(() -> failed.accept(media.getError()));
                    MediaPlayer current = new MediaPlayer(media);
                    player = current;
                    current.setOnError(() -> failed.accept(current.getError()));
                    current.setOnPlaying(started);
                    current.setOnEndOfMedia(ended);
                    current.setCycleCount(1);
                    MediaView view = new MediaView(current);
                    view.setPreserveRatio(true);
                    StackPane root = new StackPane(view);
                    root.setStyle("-fx-background-color: black;");
                    view.fitWidthProperty().bind(root.widthProperty());
                    view.fitHeightProperty().bind(root.heightProperty());
                    surface.setScene(new Scene(root, javafx.scene.paint.Color.BLACK));
                    current.play();
                } catch (RuntimeException | LinkageError failure) {
                    failed.accept(failure);
                }
            });
        }

        @Override
        public void close(Runnable afterClose) {
            closed.set(true);
            Platform.runLater(() -> {
                try {
                    if (player != null) {
                        MediaPlayer previous = player;
                        player = null;
                        try {
                            previous.stop();
                        } finally {
                            previous.dispose();
                        }
                    }
                    surface.setScene(null);
                } finally {
                    onSwing(afterClose);
                }
            });
        }
    }
}
