package com.mycompany.deadsignal;

import javax.swing.*;
import javax.swing.Timer; // Fixes the ambiguity with java.util.Timer
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

final class Main extends JPanel {

    // -------------------------------------------------
    // CHARACTER SPRITES
    // -------------------------------------------------

    // PNGs in assets/player and assets/monster are bundled with the game.
    // They are loaded from the classpath, so no computer-specific paths.
    static final class SpriteAssets {

        // One crop for all directions keeps the supplied feet and scale aligned.
        // Each direction is ordered: idle, walk 01, walk 02.
        private static final BufferedImage[] PLAYER_FRAMES = loadFrames(
                "player/player_idle_front.png",
                "player/player_walk_front_01.png",
                "player/player_walk_front_02.png",
                "player/player_idle_back.png",
                "player/player_walk_back_01.png",
                "player/player_walk_back_02.png",
                "player/player_idle_left.png",
                "player/player_walk_left_01.png",
                "player/player_walk_left_02.png",
                "player/player_idle_right.png",
                "player/player_walk_right_01.png",
                "player/player_walk_right_02.png"
        );
        static final BufferedImage[] PLAYER_FRONT = Arrays.copyOfRange(PLAYER_FRAMES, 0, 3);
        static final BufferedImage[] PLAYER_BACK = Arrays.copyOfRange(PLAYER_FRAMES, 3, 6);
        static final BufferedImage[] PLAYER_LEFT = Arrays.copyOfRange(PLAYER_FRAMES, 6, 9);
        static final BufferedImage[] PLAYER_RIGHT = Arrays.copyOfRange(PLAYER_FRAMES, 9, 12);
        // Crop all six frames together so both directions share one canvas
        // and foot baseline. The supplied artwork already contains both sides.
        private static final BufferedImage[] MONSTER_FRAMES = loadFrames(
                "monster/monster_walk_right_01.png",
                "monster/monster_walk_right_02.png",
                "monster/monster_walk_right_03.png",
                "monster/monster_walk_left_01.png",
                "monster/monster_walk_left_02.png",
                "monster/monster_walk_left_03.png"
        );
        static final BufferedImage[] MONSTER_WALK_RIGHT = Arrays.copyOfRange(MONSTER_FRAMES, 0, 3);
        static final BufferedImage[] MONSTER_WALK_LEFT = Arrays.copyOfRange(MONSTER_FRAMES, 3, 6);
        static final BufferedImage ROOM_ONE = loadSprite("rooms/room_01.png");

        // Loads a group of frames and crops them all to the SAME box, so the
        // character keeps one size and its feet stay in the same place.
        private static BufferedImage[] loadFrames(String... paths) {

            BufferedImage[] images = new BufferedImage[paths.length];

            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = -1;
            int maxY = -1;

            for (int i = 0; i < paths.length; i++) {

                images[i] = loadSprite(paths[i]);

                if (images[i] == null) {
                    continue;
                }

                for (int y = 0; y < images[i].getHeight(); y++) {
                    for (int x = 0; x < images[i].getWidth(); x++) {
                        if ((images[i].getRGB(x, y) >>> 24) != 0) {
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                        }
                    }
                }
            }

            if (maxX < minX || maxY < minY) {
                return images; // nothing visible (or nothing loaded)
            }

            for (int i = 0; i < images.length; i++) {
                if (images[i] != null) {
                    images[i] = images[i].getSubimage(minX, minY,
                            maxX - minX + 1, maxY - minY + 1);
                }
            }

            return images;
        }

        // Reads one PNG from the classpath (returns null if it is missing)
        private static BufferedImage loadSprite(String path) {

            try {
                InputStream stream =
                        Main.class.getResourceAsStream("/assets/" + path);

                if (stream == null) {
                    // Fallback for running outside the IDE's classpath
                    File file = new File("src/main/resources/assets/" + path);

                    if (!file.exists()) {
                        file = new File("assets/" + path);
                    }

                    if (!file.exists()) {
                        System.err.println("Missing sprite: assets/" + path);
                        return null;
                    }

                    stream = new FileInputStream(file);
                }

                try (InputStream input = stream) {
                    return ImageIO.read(input);
                }

            } catch (IOException | RuntimeException ex) {
                System.err.println("Could not load sprite: " + path
                        + " - " + ex.getMessage());
                return null;
            }
        }

        // Picks the animation frame. Frame 0 is the standing pose; the walk
        // cycle only plays for a moment after the character last moved.
        static BufferedImage frame(BufferedImage[] frames, long time,
                                   long lastAction, int interval) {

            if (frames == null || frames.length == 0) {
                return null;
            }

            int index = 0;

            if (time - lastAction < 350 && frames.length > 1) {
                index = (int) ((time / interval) % frames.length);
            }

            return frames[index];
        }

        // Draws a sprite with its feet at bottomY, scaled without
        // distortion (same scale on both axes), pixel-art sharp.
        static void draw(Graphics2D g, Image image, int centerX, int bottomY,
                         int maxWidth, int maxHeight, float alpha,
                         boolean flip) {

            if (image == null) {
                return;
            }

            int iw = image.getWidth(null);
            int ih = image.getHeight(null);

            if (iw <= 0 || ih <= 0) {
                return;
            }

            double scale = Math.min((double) maxWidth / iw,
                    (double) maxHeight / ih);

            int width = Math.max(1, (int) Math.round(iw * scale));
            int height = Math.max(1, (int) Math.round(ih * scale));
            int x = centerX - width / 2;
            int y = bottomY - height;

            Graphics2D copy = (Graphics2D) g.create();

            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            copy.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, alpha));

            if (flip) {
                copy.drawImage(image, x + width, y, x, y + height,
                        0, 0, iw, ih, null);
            } else {
                copy.drawImage(image, x, y, width, height, null);
            }

            copy.dispose();
        }
    }

    // -------------------------------------------------
    // CONFIGURATION (tweak everything here)
    // -------------------------------------------------

    // DEBUG = true : shows the monster, its state, debug info and the shift timer.
    // M opens the remembered map; it never reveals the monster.
    // Set to false for the real horror experience.
    static final boolean DEBUG = true;
    static final long SEED = 12345; // used for the random generator in DEBUG

    // Map layout: a 3x3 grid of rooms with hallway gaps between them
    static final int TILE = 28;
    static final int ROOM_W = 6;            // floor tiles per room (width)
    static final int ROOM_H = 4;            // floor tiles per room (height)
    static final int ROOM_COLS = 3;
    static final int ROOM_ROWS = 3;
    static final int ROOM_COUNT = ROOM_COLS * ROOM_ROWS;

    // Total tiles available for hallways between rooms. Each game splits
    // them randomly, so hallway lengths differ from game to game.
    static final int COL_GAP_TOTAL = 10;
    static final int ROW_GAP_TOTAL = 7;
    static final int[][] COL_GAP_OPTIONS = {{3, 7}, {7, 3}, {4, 6}, {6, 4}, {5, 5}};
    static final int[][] ROW_GAP_OPTIONS = {{2, 5}, {5, 2}, {3, 4}, {4, 3}};

    // Hallway classification by length (in tiles)
    static final int SHORT_HALL_MAX = 3;    // 1-3 = SHORT
    static final int MEDIUM_HALL_MAX = 5;   // 4-5 = MEDIUM, 6+ = LONG

    static final int COLS = 2 + ROOM_COLS * ROOM_W + COL_GAP_TOTAL;
    static final int ROWS = 2 + ROOM_ROWS * ROOM_H + ROW_GAP_TOTAL;
    static final int PANEL_H = 100;

    // Section ids: 0..8 are rooms, 9.. are hallways (one per connection)
    static final int HALL_BASE = ROOM_COUNT;
    static final int CONN_COUNT =
            (ROOM_COLS - 1) * ROOM_ROWS + ROOM_COLS * (ROOM_ROWS - 1);

    // Room shifting (milliseconds)
    static final long SHIFT_MIN = 120_000;          // 2 minutes
    static final long SHIFT_MAX = 180_000;          // 3 minutes
    static final long DEBUG_SHIFT_INTERVAL = 30_000; // used when DEBUG = true

    // Shift chances (per unprotected connection)
    static final double INITIAL_CLOSE_CHANCE = 0.35; // when the game starts
    static final double CLOSE_CHANCE = 0.30;         // open hallway gets sealed
    static final double OPEN_CHANCE = 0.60;          // sealed hallway reopens

    // Noise levels (distance the sound travels)
    static final int WALK_NOISE = 3;
    static final int RUN_NOISE = 8;
    static final int LOCKER_NOISE = 5;
    static final int PICKUP_NOISE = 4;

    // Item noise levels
    static final int BOTTLE_NOISE = 12;
    static final int CAN_NOISE = 10;
    static final int PIPE_NOISE = 14;
    static final int BOOK_NOISE = 7;
    static final int BOX_NOISE = 9;

    // Noise meter (HUD feedback for the player only; the monster never reads it)
    static final int NOISE_FULL_LOUDNESS = PIPE_NOISE; // loudness that fills the bar
    static final double NOISE_DECAY_PER_SECOND = 0.35; // how fast the bar falls
    static final int NOISE_SEGMENTS = 10;
    static final long RECENT_NOISE_MS = 1500;          // how long "(Running)" shows
    static final double RECENT_NOISE_MIN = 0.30;       // quieter sounds get no label

    // Every wall between a sound and the monster adds this much distance...
    static final int WALL_MUFFLE = 2;
    // ...but at most this many walls are counted (hallway gaps are thick)
    static final int MAX_MUFFLE_WALLS = 2;
    // Sound events older than this are ignored (ms)
    static final long SOUND_LIFETIME = 1000;

    // Player movement cooldowns (milliseconds per tile)
    static final long WALK_COOLDOWN = 250;
    static final long RUN_COOLDOWN = 140;

    // Monster movement intervals (milliseconds per tile)
    static final long PATROL_INTERVAL = 500;
    static final long INVESTIGATE_INTERVAL = 350;
    static final long CHASE_INTERVAL = 260; // still slower than running (140)

    // How many monster ticks it lingers at a sound before returning to patrol
    static final int SEARCH_TICKS = 6;

    // Vision (in tiles)
    static final int MONSTER_VIEW_RANGE = 7;
    static final int PLAYER_VIEW_RANGE = 8;
    // Locker checking: how close the monster must be, and the chance
    static final int LOCKER_CHECK_RANGE = 4;
    static final double LOCKER_CHECK_CHANCE = 0.5;

    // Grab and throw
    static final int GRAB_RANGE = 1;   // tiles (0 = same tile, 1 = next to you)
    static final int THROW_RANGE = 5;  // tiles an item flies before landing

    // Lockers: {room, dx, dy} where dx/dy are tiles inside the room
    static final int[][] LOCKER_SPOTS = {
            {0, 0, 3}, {1, 5, 0}, {3, 0, 0}, {5, 2, 3}, {7, 5, 3}
    };

    // Objective positions: {room, dx, dy}
    static final int[] KEY_SPOT = {2, 4, 1};
    static final int[] EXIT_SPOT = {8, 5, 2};

    // Items placed around the building (the first bottle is placed
    // next to the player's start so the grab/throw loop is easy to test)
    static final ItemType[] ITEM_POOL = {
            ItemType.CAN, ItemType.PIPE, ItemType.BOOK, ItemType.BOX,
            ItemType.BOTTLE, ItemType.CAN, ItemType.BOX, ItemType.BOOK
    };

    // Colors and fonts (created once, not every frame)
    static final Color WALL_COLOR = new Color(65, 65, 72);
    static final Color FLOOR_COLOR = new Color(22, 25, 29);
    static final Color HALL_COLOR = new Color(40, 34, 28);
    static final Color GRID_COLOR = new Color(42, 45, 49);
    static final Color LOCKER_COLOR = new Color(40, 110, 110);
    static final Color PLAYER_COLOR = new Color(50, 180, 255);
    static final Color OVERLAY_COLOR = new Color(0, 0, 0, 210);
    static final Font BIG_FONT = new Font("Arial", Font.BOLD, 28);
    static final Font SMALL_FONT = new Font("Arial", Font.PLAIN, 16);
    static final Font INFO_FONT = new Font("Arial", Font.PLAIN, 12);
    static final Font PROMPT_FONT = new Font("Arial", Font.BOLD, 14);

    // Ceiling look: dark industrial roof panels (2x2 tiles each)
    static final Color ROOM_CEILING_COLOR = new Color(47, 49, 55);
    static final Color HALL_CEILING_COLOR = new Color(40, 42, 47);
    static final Color CEILING_SEAM_COLOR = new Color(30, 31, 36);   // panel gaps
    static final Color CEILING_RIVET_COLOR = new Color(66, 68, 75);  // panel corners
    static final Color CEILING_EDGE_COLOR = new Color(17, 18, 22);   // roof outline
    static final int CEILING_PANEL = 2;  // panel size in tiles

    // How many hallway tiles stay visible next to the player's room
    // (just enough to see where a hallway entrance is)
    static final int HALL_THRESHOLD = 1;

    // Player awareness meter (how aware the MONSTER is of the player).
    // Target value per monster state comes from the real AI state.
    static final double AWARE_CHASE = 1.00;      // it sees you
    static final double AWARE_LOSE_PLAYER = 0.70; // just lost sight of you
    static final double AWARE_INVESTIGATE = 0.50; // heard YOU (steps, locker)
    static final double AWARE_SEARCH = 0.30;      // searching where it heard you
    static final double AWARE_RETURN = 0.12;      // giving up, heading back
    static final double AWARE_DISTRACTED_INVESTIGATE = 0.20; // heard a thrown item
    static final double AWARE_DISTRACTED_SEARCH = 0.10;
    static final int AWARE_NEAR_RANGE = 12;       // tiles for the closeness bonus
    static final double AWARE_PROXIMITY_BONUS = 0.20; // extra when very close
    static final double AWARE_HIDDEN_FACTOR = 0.60;   // hiding lowers it
    static final double AWARE_RISE_PER_SECOND = 1.20; // grows fast
    static final double AWARE_FALL_PER_SECOND = 0.20; // fades slowly

    static final Color AWARE_TITLE_COLOR = new Color(150, 120, 122);
    static final Color AWARE_BACK_COLOR = new Color(26, 12, 14);
    static final Color AWARE_FRAME_COLOR = new Color(80, 40, 44);
    static final Color AWARE_LOW_COLOR = new Color(100, 38, 42);
    static final Color AWARE_HIGH_COLOR = new Color(215, 55, 58);

    static final int CUE_PROXIMITY_RANGE = 10;
    static final int CUE_STING_COOLDOWN_MS = 6000;
    static final int CUE_MAX_EDGE_ALPHA = 64;
    static final int CUE_SAMPLE_RATE = 22050;
    static final long DOOR_STUN_MS = 2000;
    static final long DOOR_STUN_COOLDOWN_MS = 8000;
    static final long DOOR_FORCE_MS = 2500;
    static final long MAP_DURATION_MS = 10000;
    static final long MAP_COOLDOWN_MS = 25000;
    static final WarningSettings WARNING_SETTINGS = new WarningSettings();

    // Noise meter colors (monochrome; only "very loud" gets a faint red tint)
    static final Color NOISE_EMPTY_COLOR = new Color(45, 45, 50);
    static final Color NOISE_LOW_COLOR = new Color(120, 120, 126);
    static final Color NOISE_MID_COLOR = new Color(175, 175, 180);
    static final Color NOISE_HIGH_COLOR = new Color(230, 230, 235);
    static final Color NOISE_PULSE_A = new Color(215, 150, 150);
    static final Color NOISE_PULSE_B = new Color(140, 85, 85);

    // -------------------------------------------------
    // SOUND TYPES AND EVENTS
    // -------------------------------------------------

    enum SoundType {
        WALK(WALK_NOISE, "Footsteps"),
        RUN(RUN_NOISE, "Running"),
        LOCKER(LOCKER_NOISE, "Locker"),
        PICKUP(PICKUP_NOISE, "Object"),
        DOOR(6, "Door"),
        IMPACT(BOTTLE_NOISE, "Impact"); // thrown items use their own loudness

        final int loudness;
        final String label; // short text for the HUD

        SoundType(int loudness, String label) {
            this.loudness = loudness;
            this.label = label;
        }
    }

    static final class SoundEvent {
        final SoundType type;
        final int x;
        final int y;
        final int loudness;
        final boolean byPlayer; // false = environment / monster sound
        final long time;

        SoundEvent(SoundType type, int x, int y, int loudness,
                   boolean byPlayer, long time) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.loudness = loudness;
            this.byPlayer = byPlayer;
            this.time = time;
        }
    }

    enum MonsterState {
        PATROLLING("PATROLLING"),
        INVESTIGATING("INVESTIGATING"),
        SEARCHING("SEARCHING"),
        CHASE("CHASE"),
        LOSE_PLAYER("LOSE_PLAYER"),
        RETURN_TO_PATROL("RETURN_TO_PATROL");

        final String label;

        MonsterState(String label) {
            this.label = label;
        }
    }

    // -------------------------------------------------
    // ITEMS
    // -------------------------------------------------

    enum ItemType {
        BOTTLE("Bottle", BOTTLE_NOISE, new Color(80, 200, 120),
                "Glass clinks and rolls."),
        CAN("Can", CAN_NOISE, new Color(190, 190, 205),
                "A can rattles away."),
        PIPE("Metal pipe", PIPE_NOISE, new Color(150, 165, 185),
                "Metal clangs loudly!"),
        BOOK("Book", BOOK_NOISE, new Color(165, 100, 60),
                "A book thuds down."),
        BOX("Small box", BOX_NOISE, new Color(210, 170, 100),
                "A box scrapes and tumbles.");

        final String label;
        final int noise;       // loudness when it lands
        final Color color;
        final String impact;   // message shown when it lands

        ItemType(String label, int noise, Color color, String impact) {
            this.label = label;
            this.noise = noise;
            this.color = color;
            this.impact = impact;
        }
    }

    static final class Item {
        final ItemType type;
        int x;
        int y;

        Item(ItemType type, int x, int y) {
            this.type = type;
            this.x = x;
            this.y = y;
        }
    }

    // -------------------------------------------------
    // HALLWAY CONNECTIONS
    // -------------------------------------------------

    // One connection = one hallway between two neighboring rooms.
    // It is either open (a walkable corridor) or sealed (solid wall).
    static final class Connection {
        final int a;               // physical slot on the left / top
        final int b;               // physical slot on the right / bottom
        final boolean horizontal;  // true = runs left-right
        final int length;          // hallway length in tiles
        boolean open = true;
        int offset = 0;            // which row/column of the room it leaves from

        Connection(int a, int b, boolean horizontal, int length) {
            this.a = a;
            this.b = b;
            this.horizontal = horizontal;
            this.length = length;
        }

        // Number of possible door positions along the room's edge
        int positions() {
            return horizontal ? ROOM_H : ROOM_W;
        }
    }

    // -------------------------------------------------
    // GAME STATE
    // -------------------------------------------------

    // '#' = wall, '.' = room floor, 'h' = hallway floor
    char[][] map = new char[ROWS][COLS];

    // Which section each tile belongs to (-1 = outer wall / junction).
    // Rebuilt after room exchanges: a hallway's whole gap (the
    // corridor AND the solid tiles beside it) is one section.
    int[][] section = new int[ROWS][COLS];

    // Ceiling state per section: true = roof is on, interior is hidden
    boolean[] ceilingOn = new boolean[ROOM_COUNT + CONN_COUNT];
    final int[] roomAtSlot = new int[ROOM_COUNT];
    final int[] slotOfRoom = new int[ROOM_COUNT];
    final Door[][] roomDoors = new Door[ROOM_COUNT][4];
    final MapJournal journal = new MapJournal();
    ViewMode viewMode = ViewMode.PLAYING;
    boolean shiftPending;
    long mapOpenedAt = -MAP_COOLDOWN_MS;
    long mapClosesAt;
    long monsterStunnedUntil;
    long lastDoorStunAt = -DOOR_STUN_COOLDOWN_MS;
    boolean monsterFacingLeft;
    long lastMonsterStepAt = Long.MIN_VALUE / 2;
    Door forcingDoor;
    long doorForceStartedAt = -1;
    boolean mapKeyHeld, escapeKeyHeld;
    final HashSet<Integer> suppressedMovementKeys = new HashSet<>();
    final HashSet<Integer> heldActionKeys = new HashSet<>();
    final LongSupplier wallClock;
    boolean clockPaused;
    long pausedWallAt, pausedMovementAt, pausedMillis, pausedNanos;
    JPanel pausePanel;
    JButton resumeButton;
    GameSession ownerSession;

    int[] roomX0 = new int[ROOM_COLS];
    int[] roomY0 = new int[ROOM_ROWS];
    int[] colGap = new int[ROOM_COLS - 1];
    int[] rowGap = new int[ROOM_ROWS - 1];

    Connection[] conns = new Connection[CONN_COUNT];
    int[][] waypoints = new int[ROOM_COUNT][2];
    int[][] lockers = new int[LOCKER_SPOTS.length][2];

    ArrayList<Item> items = new ArrayList<>();
    Item held = null; // item the player is carrying

    int playerX;
    int playerY;

    int monsterX;
    int monsterY;

    int keyX;
    int keyY;

    int exitX;
    int exitY;

    // Direction the player is facing
    int faceX = 1;
    int faceY = 0;

    boolean hasKey = false;
    boolean hidden = false; // hiding in a locker
    boolean gameOver = false;
    boolean won = false;

    int shiftNumber = 0;

    // Monster's current investigation target (-1 = none)
    int targetX = -1;
    int targetY = -1;

    // Where the monster last SAW the player
    int lastSeenX = -1;
    int lastSeenY = -1;

    // Monster's current patrol destination
    int patrolX = -1;
    int patrolY = -1;

    int searchTicksLeft = 0;
    boolean[] lockerChecked = new boolean[LOCKER_SPOTS.length];

    long nextShiftAt;
    long lastMonsterMove = 0;
    long lastPlayerMove = 0;
    long lastPlayerStepNanos = 0;
    boolean playerHasStepped = false;
    final LongSupplier movementClock;

    final LinkedHashMap<Integer, Point> heldMovementKeys = new LinkedHashMap<>();
    boolean runningHeld = false;
    final Timer movementTimer = new Timer(16, e -> updatePlayerMovement());
    final Timer gameTimer = new Timer(50, e -> updateGame());
    Window movementWindow;
    final WindowAdapter movementFocusListener = new WindowAdapter() {
        @Override
        public void windowLostFocus(WindowEvent e) {
            clearAllControls();
        }
    };

    String message = "Find the keycard and escape!";
    MonsterState monsterState = MonsterState.PATROLLING;

    Queue<SoundEvent> soundQueue = new LinkedList<>();

    // Player noise meter: 0.0 (silent) to 1.0 (as loud as it gets)
    double noise = 0;
    long lastNoiseUpdate = 0;
    String lastNoiseText = "";
    long lastNoiseTextTime = 0;

    // Player awareness meter: 0.0 (monster unaware) to 1.0 (hunting you)
    double awareness = 0;
    double awarenessTarget = 0;
    long lastAwareUpdate = 0;
    final WarningCues warningCues = new WarningCues();
    boolean warningSettingsOpen = false;
    // True when the monster is investigating a THROWN item, not the player
    boolean heardDistraction = false;

    // DEBUG only: the most recent sound and whether the monster heard it
    SoundType lastSoundType = null;
    int lastSoundLoudness = 0;
    boolean lastSoundHeard = false;

    Random random = DEBUG ? new Random(SEED) : new Random();

    public Main() {
        this(System::nanoTime);
    }

    Main(LongSupplier movementClock) {
        this(movementClock, System::currentTimeMillis);
    }

    Main(LongSupplier movementClock, LongSupplier wallClock) {
        this.movementClock = movementClock;
        this.wallClock = wallClock;
        setPreferredSize(new Dimension(COLS * TILE, ROWS * TILE + PANEL_H));
        setBackground(Color.BLACK);
        setFocusable(true);
        setupControls();
        initPausePanel();
        restartGame();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        clearAllControls();
        movementWindow = SwingUtilities.getWindowAncestor(this);
        if (movementWindow != null) {
            movementWindow.addWindowFocusListener(movementFocusListener);
        }
        movementTimer.start();
        warningCues.audio.start();
        gameTimer.start();
    }

    @Override
    public void removeNotify() {
        movementTimer.stop();
        gameTimer.stop();
        clearMovementInput();
        if (movementWindow != null) {
            movementWindow.removeWindowFocusListener(movementFocusListener);
            movementWindow = null;
        }
        warningCues.audio.close();
        super.removeNotify();
    }

    double nearbyMonsterLevel() {
        int distance = Math.abs(monsterX - playerX) + Math.abs(monsterY - playerY);
        distance += WALL_MUFFLE * Math.min(MAX_MUFFLE_WALLS,
                wallsBetween(playerX, playerY, monsterX, monsterY));
        double level = Math.max(0, 1.0 - distance / (double) CUE_PROXIMITY_RANGE);
        return level * level * (hidden ? AWARE_HIDDEN_FACTOR : 1.0);
    }

    void updateWarningCues(long now) {
        Window window = SwingUtilities.getWindowAncestor(this);
        boolean active = !gameOver && !won && !warningSettingsOpen
                && (viewMode == ViewMode.PLAYING || viewMode == ViewMode.MAP)
                && isShowing() && window != null && window.isFocused();
        warningCues.update(awareness, nearbyMonsterLevel(), active, now);
    }

    void openWarningSettings() {
        if (warningSettingsOpen || viewMode != ViewMode.PLAYING) return;
        WarningPause pause = pauseWarningSettings();
        try {
            showWarningSettings(this);
        } finally {
            resumeWarningSettings(pause);
        }
    }

    record WarningPause(long openedAt, long movementAt,
                        boolean gameTimerRunning, boolean movementTimerRunning) { }

    WarningPause pauseWarningSettings() {
        WarningPause pause = new WarningPause(gameNow(), movementNow(),
                gameTimer.isRunning(), movementTimer.isRunning());
        warningSettingsOpen = true;
        pauseSimulation();
        movementTimer.stop();
        gameTimer.stop();
        clearAllControls();
        return pause;
    }

    void resumeWarningSettings(WarningPause pause) {
        warningSettingsOpen = false;
        resumeSimulation();
        clearAllControls();
        updateWarningCues(gameNow());
        if (isDisplayable()) {
            if (pause.movementTimerRunning()) movementTimer.start();
            if (pause.gameTimerRunning()) gameTimer.start();
        }
        repaint();
    }

    // -------------------------------------------------
    // MAP GENERATION
    // -------------------------------------------------

    // Returns the {x, y} tile at (dx, dy) inside a room
    int[] roomTile(int room, int dx, int dy) {
        int slot = slotOfRoom[room];
        return new int[] {roomX0[slot % ROOM_COLS] + dx, roomY0[slot / ROOM_COLS] + dy};
    }

    // Picks hallway lengths, places rooms and builds the first layout
    void buildMap() {
        initRoomIdentities();

        // 1. Choose how long the hallways between room columns/rows are
        colGap = COL_GAP_OPTIONS[random.nextInt(COL_GAP_OPTIONS.length)];
        rowGap = ROW_GAP_OPTIONS[random.nextInt(ROW_GAP_OPTIONS.length)];

        // 2. Rooms sit on a grid; the gaps between them become hallways
        roomX0[0] = 1;
        for (int c = 1; c < ROOM_COLS; c++) {
            roomX0[c] = roomX0[c - 1] + ROOM_W + colGap[c - 1];
        }

        roomY0[0] = 1;
        for (int r = 1; r < ROOM_ROWS; r++) {
            roomY0[r] = roomY0[r - 1] + ROOM_H + rowGap[r - 1];
        }

        // 3. Room centers (patrol waypoints), lockers, key and exit
        for (int room = 0; room < ROOM_COUNT; room++) {
            waypoints[room] = roomTile(room, ROOM_W / 2, ROOM_H / 2);
        }

        for (int i = 0; i < LOCKER_SPOTS.length; i++) {
            lockers[i] = roomTile(
                    LOCKER_SPOTS[i][0], LOCKER_SPOTS[i][1], LOCKER_SPOTS[i][2]
            );
        }

        int[] k = roomTile(KEY_SPOT[0], KEY_SPOT[1], KEY_SPOT[2]);
        keyX = k[0];
        keyY = k[1];

        int[] e = roomTile(EXIT_SPOT[0], EXIT_SPOT[1], EXIT_SPOT[2]);
        exitX = e[0];
        exitY = e[1];

        // 4. Create one connection for every pair of neighboring rooms
        int n = 0;

        for (int r = 0; r < ROOM_ROWS; r++) {
            for (int c = 0; c < ROOM_COLS - 1; c++) {
                conns[n++] = new Connection(
                        r * ROOM_COLS + c, r * ROOM_COLS + c + 1,
                        true, colGap[c]
                );
            }
        }

        for (int r = 0; r < ROOM_ROWS - 1; r++) {
            for (int c = 0; c < ROOM_COLS; c++) {
                conns[n++] = new Connection(
                        r * ROOM_COLS + c, (r + 1) * ROOM_COLS + c,
                        false, rowGap[r]
                );
            }
        }

        initSections();

        for (Connection cn : conns) {
            cn.offset = random.nextInt(cn.positions());
        }

        // 5. Seal some hallways, but never disconnect the building
        for (int i : shuffledIndices()) {
            if (random.nextDouble() < INITIAL_CLOSE_CHANCE) {
                conns[i].open = false;

                if (!roomsConnected()) {
                    conns[i].open = true;
                }
            }
        }

        applyLayout();
        rebuildDoors();
    }

    // Rebuilds the whole map from the room grid + the open connections.
    // Room footprints stay fixed; room identities and contents exchange slots.
    void applyLayout() {

        // Everything starts as solid wall
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                map[y][x] = '#';
            }
        }

        // Carve the rooms
        for (int room = 0; room < ROOM_COUNT; room++) {
            int[] origin = roomTile(room, 0, 0);

            for (int dy = 0; dy < ROOM_H; dy++) {
                for (int dx = 0; dx < ROOM_W; dx++) {
                    map[origin[1] + dy][origin[0] + dx] = '.';
                }
            }
        }

        // Carve the open hallways
        for (int i = 0; i < conns.length; i++) {
            if (conns[i].open) {
                carveHallway(i);
            }
        }
    }

    // Marks the current room identity or hallway for each tile, so a
    // covered hallway looks the same whether it is open or sealed.
    void initSections() {

        for (int[] row : section) {
            Arrays.fill(row, -1);
        }

        for (int room = 0; room < ROOM_COUNT; room++) {
            int[] origin = roomTile(room, 0, 0);

            for (int dy = 0; dy < ROOM_H; dy++) {
                for (int dx = 0; dx < ROOM_W; dx++) {
                    section[origin[1] + dy][origin[0] + dx] = room;
                }
            }
        }

        for (int i = 0; i < conns.length; i++) {

            Connection c = conns[i];

            int colA = c.a % ROOM_COLS;
            int rowA = c.a / ROOM_COLS;

            int x0 = roomX0[colA];
            int y0 = roomY0[rowA];
            int x1;
            int y1;

            if (c.horizontal) {
                x0 += ROOM_W;                 // gap to the right of room A
                x1 = roomX0[colA + 1] - 1;
                y1 = y0 + ROOM_H - 1;
            } else {
                y0 += ROOM_H;                 // gap below room A
                x1 = x0 + ROOM_W - 1;
                y1 = roomY0[rowA + 1] - 1;
            }

            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    section[y][x] = HALL_BASE + i;
                }
            }
        }
    }

    // Draws one hallway: a straight corridor across the gap between two rooms
    void carveHallway(int i) {

        Connection c = conns[i];

        int colA = c.a % ROOM_COLS;
        int rowA = c.a / ROOM_COLS;

        if (c.horizontal) {

            int y = roomY0[rowA] + c.offset;

            for (int x = roomX0[colA] + ROOM_W;
                 x < roomX0[colA + 1]; x++) {
                map[y][x] = 'h';
            }

        } else {

            int x = roomX0[colA] + c.offset;

            for (int y = roomY0[rowA] + ROOM_H;
                 y < roomY0[rowA + 1]; y++) {
                map[y][x] = 'h';
            }
        }
    }

    boolean isWall(int x, int y) {
        Door door = doorAt(x, y);
        return terrainWall(x, y) || (door != null && !door.open);
    }

    int sectionAt(int x, int y) {
        if (x < 0 || y < 0 ||
                x >= COLS || y >= ROWS) {
            return -1;
        }

        return section[y][x];
    }

    String hallType(int length) {
        if (length <= SHORT_HALL_MAX) {
            return "SHORT";
        }

        if (length <= MEDIUM_HALL_MAX) {
            return "MEDIUM";
        }

        return "LONG";
    }

    String sectionName(int sec) {
        if (sec < 0) {
            return "none";
        }

        if (sec < ROOM_COUNT) {
            return "Room " + sec;
        }

        int i = sec - HALL_BASE;

        return "Hall " + i + " (" + hallType(conns[i].length) + ")";
    }

    // Which locker is at this tile? (-1 = none)
    int lockerAt(int x, int y) {
        for (int i = 0; i < lockers.length; i++) {
            if (lockers[i][0] == x && lockers[i][1] == y) {
                return i;
            }
        }
        return -1;
    }

    // True if every room can still be reached from every other room
    boolean roomsConnected() {

        boolean[] seen = new boolean[ROOM_COUNT];
        seen[0] = true;

        boolean grew = true;

        while (grew) {
            grew = false;

            for (Connection c : conns) {
                if (c.open && seen[c.a] != seen[c.b]) {
                    seen[c.a] = true;
                    seen[c.b] = true;
                    grew = true;
                }
            }
        }

        for (boolean s : seen) {
            if (!s) {
                return false;
            }
        }

        return true;
    }

    int[] shuffledIndices() {

        int[] a = new int[conns.length];

        for (int i = 0; i < a.length; i++) {
            a[i] = i;
        }

        for (int i = a.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }

        return a;
    }

    // -------------------------------------------------
    // LINE OF SIGHT HELPERS
    // -------------------------------------------------

    // Counts wall tiles between two tiles (the end tiles don't count)
    int wallsBetween(int x0, int y0, int x1, int y1) {

        int count = 0;

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0;
        int y = y0;

        while (!(x == x1 && y == y1)) {

            int e2 = 2 * err;

            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }

            if (e2 < dx) {
                err += dx;
                y += sy;
            }

            if (!(x == x1 && y == y1) && isWall(x, y)) {
                count++;
            }
        }

        return count;
    }

    // True if two tiles are within range and nothing blocks the view
    boolean lineClear(int x0, int y0, int x1, int y1, int range) {

        int dist = Math.abs(x0 - x1) + Math.abs(y0 - y1);

        return dist <= range &&
                wallsBetween(x0, y0, x1, y1) == 0;
    }

    boolean monsterSeesPlayer() {
        return !hidden && lineClear(
                monsterX, monsterY,
                playerX, playerY,
                MONSTER_VIEW_RANGE
        );
    }

    boolean playerSeesMonster() {
        return lineClear(
                playerX, playerY,
                monsterX, monsterY,
                PLAYER_VIEW_RANGE
        );
    }

    // -------------------------------------------------
    // ROOM CEILINGS (cover system)
    // -------------------------------------------------

    // Every room and every hallway strip has a ceiling. ONLY the section the
    // player is physically standing in loses its ceiling (a room or a
    // hallway). That section is always protected from shifting, so nothing
    // the player can see ever changes.
    void updateCeilings() {

        Arrays.fill(ceilingOn, true);

        int cur = sectionAt(playerX, playerY);

        if (cur < 0) {
            return;
        }

        ceilingOn[cur] = false;
    }

    // True if this tile is the "mouth" of an OPEN hallway attached to the room
    // the player is standing in: the first HALL_THRESHOLD tiles next to that
    // room. Purely visual. Sealed hallways never show a mouth, and the rest
    // of the hallway keeps its ceiling. Hallways attached to the player's room
    // are frozen during a shift, so the mouth can never change while visible.
    boolean isThreshold(int x, int y) {

        int s = sectionAt(x, y);
        int cur = sectionAt(playerX, playerY);

        if (s < HALL_BASE || cur < 0 || cur >= ROOM_COUNT) {
            return false;
        }

        Connection c = conns[s - HALL_BASE];

        if (!c.open || map[y][x] != 'h' || (roomAtSlot[c.a] != cur && roomAtSlot[c.b] != cur)) {
            return false;
        }

        int colA = c.a % ROOM_COLS;
        int rowA = c.a / ROOM_COLS;

        // Distance in tiles from the player's room to this hallway tile
        int dist;

        if (c.horizontal) {
            dist = (roomAtSlot[c.a] == cur)
                    ? x - (roomX0[colA] + ROOM_W)
                    : (roomX0[colA + 1] - 1) - x;
        } else {
            dist = (roomAtSlot[c.a] == cur)
                    ? y - (roomY0[rowA] + ROOM_H)
                    : (roomY0[rowA + 1] - 1) - y;
        }

        return dist < HALL_THRESHOLD;
    }

    // True if a roof is hiding this tile
    boolean isCovered(int x, int y) {

        int s = sectionAt(x, y);

        return s >= 0 && ceilingOn[s] &&
                !isThreshold(x, y);
    }

    // True where a roof tile borders a different section or an open tile,
    // so the roof gets a dark outline there (including around a mouth)
    boolean roofEdge(int x, int y, int sec) {
        return sectionAt(x, y) != sec || !isCovered(x, y);
    }

    boolean canSeeTile(int x, int y) {
        if (x < 0 || y < 0 || x >= COLS || y >= ROWS) {
            return false;
        }

        return !isCovered(x, y);
    }

    // -------------------------------------------------
    // PLAYER CONTROLS
    // -------------------------------------------------

    void setupControls() {

        bindMovementKey(KeyEvent.VK_W, 0, -1);
        bindMovementKey(KeyEvent.VK_A, -1, 0);
        bindMovementKey(KeyEvent.VK_S, 0, 1);
        bindMovementKey(KeyEvent.VK_D, 1, 0);
        bindMovementKey(KeyEvent.VK_UP, 0, -1);
        bindMovementKey(KeyEvent.VK_LEFT, -1, 0);
        bindMovementKey(KeyEvent.VK_DOWN, 0, 1);
        bindMovementKey(KeyEvent.VK_RIGHT, 1, 0);

        bindModifierVariants(KeyEvent.VK_SHIFT, false, "run-pressed", () -> {
            runningHeld = true;
            updatePlayerMovement();
        });
        bindModifierVariants(KeyEvent.VK_SHIFT, true, "run-released", () -> {
            runningHeld = false;
            updatePlayerMovement();
        });

        bindGameplayAction(KeyEvent.VK_E, "grab", this::grabItem, false);
        bindGameplayAction(KeyEvent.VK_Q, "throw", this::throwItem, false);
        bindGameplayAction(KeyEvent.VK_F, "locker", this::toggleLocker, false);
        bindGameplayAction(KeyEvent.VK_R, "restart", this::restartGame, true);
        bindGameplayAction(KeyEvent.VK_P, "warning-settings", this::openWarningSettings, false);
        bindGameplayAction(KeyEvent.VK_SPACE, "door", this::toggleDoor, false);
        bindAction("M", "map", () -> {
            if (!mapKeyHeld) { mapKeyHeld = true; toggleMap(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.SHIFT_DOWN_MASK, false), "map");
        bindModifierVariants(KeyEvent.VK_M, true, "map-released", () -> mapKeyHeld = false);
        bindAction("ESCAPE", "escape", () -> {
            if (!escapeKeyHeld) { escapeKeyHeld = true; handleEscape(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.SHIFT_DOWN_MASK, false), "escape");
        bindModifierVariants(KeyEvent.VK_ESCAPE, true, "escape-released", () -> escapeKeyHeld = false);
    }

    void bindAction(String key, String name, Runnable action) {
        bindAction(KeyStroke.getKeyStroke(key), name, action);
    }

    void bindAction(KeyStroke key, String name, Runnable action) {

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                key, name
        );

        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    void bindMovementKey(int keyCode, int dx, int dy) {
        for (int modifiers : new int[] {0, InputEvent.SHIFT_DOWN_MASK}) {
            boolean running = modifiers != 0;
            bindAction(KeyStroke.getKeyStroke(keyCode, modifiers, false),
                    "move-pressed-" + keyCode + "-" + modifiers, () -> {
                if (inputLocked() || hidden) {
                    suppressedMovementKeys.add(keyCode);
                    clearMovementInput();
                    return;
                }
                if (suppressedMovementKeys.contains(keyCode)) return;
                runningHeld = running;
                // Keyboard repeat must not change which direction was pressed last.
                if (!heldMovementKeys.containsKey(keyCode)) {
                    heldMovementKeys.put(keyCode, new Point(dx, dy));
                    updatePlayerMovement();
                }
            });
        }
        bindModifierVariants(keyCode, true, "move-released-" + keyCode, () -> {
            heldMovementKeys.remove(keyCode);
            suppressedMovementKeys.remove(keyCode);
            updatePlayerMovement();
        });
    }

    void bindModifierVariants(int keyCode, boolean released, String name,
                              Runnable action) {
        bindAction(KeyStroke.getKeyStroke(keyCode, 0, released), name, action);
        // A modifier may change before a held key is released.
        int[] masks = {InputEvent.SHIFT_DOWN_MASK, InputEvent.CTRL_DOWN_MASK,
                InputEvent.ALT_DOWN_MASK, InputEvent.META_DOWN_MASK,
                InputEvent.ALT_GRAPH_DOWN_MASK};
        for (int combination = 1; combination < (1 << masks.length); combination++) {
            int modifiers = 0;
            for (int bit = 0; bit < masks.length; bit++) {
                if ((combination & (1 << bit)) != 0) {
                    modifiers |= masks[bit];
                }
            }
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                    KeyStroke.getKeyStroke(keyCode, modifiers, released), name);
        }
    }

    void clearMovementInput() {
        heldMovementKeys.clear();
        runningHeld = false;
    }

    void updatePlayerMovement() {
        updatePlayerMovement(movementNow());
    }

    void updatePlayerMovement(long nowNanos) {
        if (inputLocked() || hidden) {
            clearMovementInput();
            return;
        }
        Point direction = null;
        for (Point heldDirection : heldMovementKeys.values()) {
            direction = heldDirection;
        }
        if (direction != null) {
            movePlayer(direction.x, direction.y, runningHeld, nowNanos);
        }
    }

    void movePlayer(int dx, int dy, boolean running) {
        movePlayer(dx, dy, running, movementNow());
    }

    void movePlayer(int dx, int dy, boolean running, long nowNanos) {

        if (inputLocked()) {
            return;
        }

        if (hidden) {
            message = "You are hiding. Press F to leave the locker.";
            repaint();
            return;
        }

        // Movement cooldown: running is faster but louder
        long cooldown = running ? RUN_COOLDOWN : WALK_COOLDOWN;

        if (playerHasStepped &&
                nowNanos - lastPlayerStepNanos < cooldown * 1_000_000L) {
            return;
        }

        faceX = dx;
        faceY = dy;

        int nx = playerX + dx;
        int ny = playerY + dy;

        if (isWall(nx, ny)) {
            message = "The path is blocked.";
            repaint();
            return;
        }

        playerX = nx;
        playerY = ny;
        lastPlayerStepNanos = nowNanos;
        playerHasStepped = true;
        lastPlayerMove = gameNow();

        // Every step makes a sound; the player is never told if it was heard
        emitSound(running ? SoundType.RUN : SoundType.WALK,
                playerX, playerY);

        if (running) {
            message = "You are running! The noise is dangerous.";
        } else {
            message = "You move carefully...";
        }

        // Collect the keycard
        if (!hasKey &&
                playerX == keyX &&
                playerY == keyY) {

            hasKey = true;
            message = "KEYCARD ACQUIRED!";
        }

        // Check the exit
        if (playerX == exitX && playerY == exitY) {

            if (hasKey) {
                won = true;
                message = "YOU ESCAPED!";
            } else {
                message = "The exit is locked. Find the keycard!";
            }
        }

        observeArea();
        checkCollision();
        if (gameOver || won) {
            clearMovementInput();
        }
        repaint();
    }

    // -------------------------------------------------
    // GRAB & THROW
    // -------------------------------------------------

    // Closest item the player can reach (null if none)
    Item nearestItem() {

        Item best = null;
        int bestDist = GRAB_RANGE + 1;

        for (Item it : items) {

            int d = Math.abs(it.x - playerX) + Math.abs(it.y - playerY);

            // No reaching through walls
            if (d <= GRAB_RANGE && d < bestDist &&
                    wallsBetween(playerX, playerY, it.x, it.y) == 0) {
                best = it;
                bestDist = d;
            }
        }

        return best;
    }

    // E key: pick up the closest item
    void grabItem() {

        if (inputLocked()) {
            return;
        }

        if (hidden) {
            message = "You can't grab anything from inside a locker.";
            repaint();
            return;
        }

        if (held != null) {
            message = "Your hands are full. Press Q to throw the "
                    + held.type.label + ".";
            repaint();
            return;
        }

        Item it = nearestItem();

        if (it == null) {
            message = "There is nothing here to grab.";
            repaint();
            return;
        }

        items.remove(it);
        held = it;

        emitSound(SoundType.PICKUP, playerX, playerY); // small noise

        message = "You pick up the " + it.type.label + ".";
        repaint();
    }

    // Q key: throw the held item in the direction the player faces
    void throwItem() {

        if (inputLocked()) {
            return;
        }

        if (hidden) {
            message = "You can't throw anything from inside a locker.";
            repaint();
            return;
        }

        if (held == null) {
            message = "You aren't holding anything. Press E near an item.";
            repaint();
            return;
        }

        // The item flies forward until it hits a wall or runs out of range
        int bx = playerX;
        int by = playerY;

        for (int i = 0; i < THROW_RANGE; i++) {

            int nx = bx + faceX;
            int ny = by + faceY;

            if (isWall(nx, ny)) {
                break;
            }

            bx = nx;
            by = ny;
        }

        Item thrown = held;
        held = null;

        thrown.x = bx;
        thrown.y = by;
        items.add(thrown);

        // The sound comes from the landing position, using the item's loudness
        emitSound(SoundType.IMPACT, bx, by, thrown.type.noise);

        message = "You throw the " + thrown.type.label
                + ". " + thrown.type.impact;

        repaint();
    }

    // Press F on a locker tile to hide or to leave
    void toggleLocker() {

        if (inputLocked()) {
            return;
        }

        if (hidden) {
            hidden = false;
            emitSound(SoundType.LOCKER, playerX, playerY);
            message = "You step out of the locker.";
            repaint();
            return;
        }

        if (lockerAt(playerX, playerY) < 0) {
            message = "Stand on a locker and press F to hide.";
            repaint();
            return;
        }

        // Can't hide while the monster can see you
        if (lineClear(monsterX, monsterY, playerX, playerY,
                MONSTER_VIEW_RANGE)) {
            message = "It's watching you. You can't hide!";
            repaint();
            return;
        }

        hidden = true;
        clearMovementInput();
        emitSound(SoundType.LOCKER, playerX, playerY);
        message = "You hide in the locker. Stay quiet...";
        repaint();
    }

    // Text shown on the HUD, e.g. "[E] GRAB BOTTLE" (empty if nothing to do)
    String interactionPrompt() {
        if (viewMode != ViewMode.PLAYING) return "";
        Door door = facingDoor();
        if (!hidden && door != null) return "[SPACE] " + (door.open ? "CLOSE DOOR" : "OPEN DOOR");

        if (gameOver || won) {
            return "";
        }

        if (hidden) {
            return "[F] LEAVE LOCKER";
        }

        if (held != null) {
            return "[Q] THROW " + held.type.label.toUpperCase();
        }

        Item it = nearestItem();

        if (it != null) {
            return "[E] GRAB " + it.type.label.toUpperCase();
        }

        if (lockerAt(playerX, playerY) >= 0) {
            return "[F] HIDE IN LOCKER";
        }

        return "";
    }

    // -------------------------------------------------
    // ITEM PLACEMENT
    // -------------------------------------------------

    boolean itemAt(int x, int y) {
        for (Item it : items) {
            if (it.x == x && it.y == y) {
                return true;
            }
        }
        return false;
    }

    boolean tileIsFree(int x, int y) {
        return !isWall(x, y) &&
                lockerAt(x, y) < 0 &&
                !(x == keyX && y == keyY) &&
                !(x == exitX && y == exitY) &&
                !(x == playerX && y == playerY) &&
                !(x == monsterX && y == monsterY) &&
                !itemAt(x, y);
    }

    // A random unused floor tile inside a room
    Point freeTileInRoom(int room) {

        Point last = null;

        for (int tries = 0; tries < 40; tries++) {

            int[] t = roomTile(
                    room, random.nextInt(ROOM_W), random.nextInt(ROOM_H)
            );

            last = new Point(t[0], t[1]);

            if (tileIsFree(t[0], t[1])) {
                return last;
            }
        }

        return last;
    }

    // Start of game: a bottle near the player, other items in random rooms
    void spawnItems() {

        items.clear();
        held = null;

        int[] b = roomTile(0, 3, 1);
        items.add(new Item(ItemType.BOTTLE, b[0], b[1]));

        for (ItemType type : ITEM_POOL) {

            int room = 1 + random.nextInt(ROOM_COUNT - 1);
            Point p = freeTileInRoom(room);

            items.add(new Item(type, p.x, p.y));
        }
    }

    // -------------------------------------------------
    // SOUND SYSTEM
    // -------------------------------------------------

    // Anything in the game can call this to make noise.
    // Sounds made through these two methods count as PLAYER noise.
    void emitSound(SoundType type, int x, int y) {
        emitSound(type, x, y, type.loudness);
    }

    // Same, but with a custom loudness (thrown items)
    void emitSound(SoundType type, int x, int y, int loudness) {
        emitSound(type, x, y, loudness, true);
    }

    // For sounds the player did not make (building creaks, monster steps).
    // The monster can hear them, but they do NOT raise the noise meter.
    void emitEnvironmentalSound(SoundType type, int x, int y, int loudness) {
        emitSound(type, x, y, loudness, false);
    }

    // The one place every sound goes through
    void emitSound(SoundType type, int x, int y, int loudness,
                   boolean byPlayer) {

        long now = gameNow();

        soundQueue.add(new SoundEvent(
                type, x, y, loudness, byPlayer, now
        ));

        lastSoundType = type;
        lastSoundLoudness = loudness;

        if (byPlayer) {
            addPlayerNoise(type, loudness, now);
        }
    }

    // -------------------------------------------------
    // PLAYER NOISE METER
    // -------------------------------------------------

    // Raises the meter using the sound's existing loudness value.
    // This only tells the PLAYER how loud they were. The monster decides
    // what it hears in monsterHears(), never from this meter.
    void addPlayerNoise(SoundType type, int loudness, long now) {

        double level = Math.min(1.0, (double) loudness / NOISE_FULL_LOUDNESS);

        noise = Math.max(noise, level);

        if (level >= RECENT_NOISE_MIN) {
            lastNoiseText = type.label;
            lastNoiseTextTime = now;
        }
    }

    // Called every game tick: the meter slowly falls back to zero
    void updateNoise(long now) {

        double seconds = (now - lastNoiseUpdate) / 1000.0;

        lastNoiseUpdate = now;

        noise = Math.max(0, noise - NOISE_DECAY_PER_SECOND * seconds);
    }

    String noiseLabel() {

        if (noise < 0.05) {
            return "QUIET";
        }

        if (noise < 0.30) {
            return "LOW";
        }

        if (noise < 0.55) {
            return "NOTICEABLE";
        }

        if (noise < 0.80) {
            return "LOUD";
        }

        return "VERY LOUD";
    }

    // SOUND LEVEL meter: blocky 10-segment bar (how loud the PLAYER is)
    void drawSoundMeter(Graphics2D g2, int x, int y) {

        long now = gameNow();

        Color c;

        if (noise >= 0.80) {
            // Very loud: slow pulse between two dim reds
            c = ((now / 150) % 2 == 0) ? NOISE_PULSE_A : NOISE_PULSE_B;
        } else if (noise >= 0.55) {
            c = NOISE_HIGH_COLOR;
        } else if (noise >= 0.30) {
            c = NOISE_MID_COLOR;
        } else {
            c = NOISE_LOW_COLOR;
        }

        g2.setColor(NOISE_LOW_COLOR);
        g2.drawString("SOUND LEVEL", x, y);

        g2.setColor(c);
        g2.drawString(noiseLabel(), x + 92, y);

        int filled = noise < 0.02
                ? 0 : (int) Math.ceil(noise * NOISE_SEGMENTS);

        for (int i = 0; i < NOISE_SEGMENTS; i++) {
            g2.setColor(i < filled ? c : NOISE_EMPTY_COLOR);
            g2.fillRect(x + i * 13, y + 5, 11, 9);
        }

        // Which action made the noise (only for noticeable sounds)
        if (now - lastNoiseTextTime < RECENT_NOISE_MS &&
                !lastNoiseText.isEmpty()) {
            g2.setColor(NOISE_LOW_COLOR);
            g2.drawString("(" + lastNoiseText + ")", x + 136, y + 14);
        }
    }

    // -------------------------------------------------
    // PLAYER AWARENESS METER
    // -------------------------------------------------

    // What the monster's AI state says about how aware it is of the PLAYER.
    // The player only ever sees a bar, never where the monster is.
    double computeAwarenessTarget() {

        if (monsterState == MonsterState.CHASE) {
            return AWARE_CHASE; // it can see the player right now
        }

        double base;

        switch (monsterState) {
            case LOSE_PLAYER -> base = AWARE_LOSE_PLAYER;
            case INVESTIGATING -> base = heardDistraction
                    ? AWARE_DISTRACTED_INVESTIGATE : AWARE_INVESTIGATE;
            case SEARCHING -> base = heardDistraction
                    ? AWARE_DISTRACTED_SEARCH : AWARE_SEARCH;
            case RETURN_TO_PATROL -> base = AWARE_RETURN;
            default -> base = 0; // patrolling: unaware
        }

        if (base <= 0) {
            return 0;
        }

        // Closer to the monster = it is more aware of you
        int dist = Math.abs(monsterX - playerX) + Math.abs(monsterY - playerY);

        double closeness =
                1.0 - Math.min(dist, AWARE_NEAR_RANGE) / (double) AWARE_NEAR_RANGE;

        base += AWARE_PROXIMITY_BONUS * closeness;

        // Hiding in a locker: the monster cannot see you
        if (hidden) {
            base *= AWARE_HIDDEN_FACTOR;
        }

        return Math.min(base, 0.95); // only a real chase reaches 100%
    }

    // Called every game tick: the bar rises quickly and fades slowly
    void updateAwareness(long now) {

        double seconds = (now - lastAwareUpdate) / 1000.0;

        lastAwareUpdate = now;

        awarenessTarget = computeAwarenessTarget();

        if (awarenessTarget > awareness) {
            awareness = Math.min(awarenessTarget,
                    awareness + AWARE_RISE_PER_SECOND * seconds);
        } else {
            awareness = Math.max(awarenessTarget,
                    awareness - AWARE_FALL_PER_SECOND * seconds);
        }
    }

    String awarenessLabel() {

        if (awareness < 0.08) {
            return "UNAWARE";
        }

        if (awareness < 0.35) {
            return "SUSPICIOUS";
        }

        if (awareness < 0.70) {
            return "ALERT";
        }

        return "HUNTED";
    }

    // PLAYER AWARENESS meter: smooth dark-red bar with a frame and ticks
    void drawAwarenessMeter(Graphics2D g2, int x, int y) {

        long now = gameNow();

        int barW = NOISE_SEGMENTS * 13 - 2; // same width as the sound meter

        // Color goes from dull red to a stronger red as awareness grows
        double a = Math.max(0, Math.min(1, awareness));

        int r = (int) (AWARE_LOW_COLOR.getRed()
                + a * (AWARE_HIGH_COLOR.getRed() - AWARE_LOW_COLOR.getRed()));
        int gr = (int) (AWARE_LOW_COLOR.getGreen()
                + a * (AWARE_HIGH_COLOR.getGreen() - AWARE_LOW_COLOR.getGreen()));
        int b = (int) (AWARE_LOW_COLOR.getBlue()
                + a * (AWARE_HIGH_COLOR.getBlue() - AWARE_LOW_COLOR.getBlue()));

        // When it is hunting you the bar throbs
        if (a >= 0.70 && !WARNING_SETTINGS.reducedEffects) {
            double pulse = 0.78 + 0.22 * warningCues.pulse();
            r = (int) (r * pulse);
            gr = (int) (gr * pulse);
            b = (int) (b * pulse);
        }

        Color c = new Color(r, gr, b);

        g2.setColor(AWARE_TITLE_COLOR);
        g2.drawString("PLAYER AWARENESS", x, y);

        g2.setColor(a < 0.08 ? NOISE_LOW_COLOR : c.brighter());
        g2.drawString(awarenessLabel(), x + 140, y);

        // Frame and dark background
        g2.setColor(AWARE_BACK_COLOR);
        g2.fillRect(x, y + 5, barW, 9);

        // Filled part
        int fill = (int) Math.round(a * (barW - 4));

        g2.setColor(c);
        g2.fillRect(x + 2, y + 7, fill, 5);

        // Tick marks at 25%, 50% and 75%
        g2.setColor(AWARE_FRAME_COLOR);

        for (int i = 1; i <= 3; i++) {
            g2.fillRect(x + barW * i / 4, y + 5, 1, 9);
        }

        g2.drawRect(x, y + 5, barW - 1, 8);
    }

    // The monster listens to everything that was emitted
    void processSounds() {

        long now = gameNow();

        while (!soundQueue.isEmpty()) {

            SoundEvent sound = soundQueue.poll();

            // Old sounds are forgotten; a chasing monster ignores noise
            if (now - sound.time > SOUND_LIFETIME ||
                    monsterState == MonsterState.CHASE) {
                continue;
            }

            boolean heard = monsterHears(sound);
            lastSoundHeard = heard;

            if (heard) {
                targetX = sound.x;
                targetY = sound.y;
                monsterState = MonsterState.INVESTIGATING;

                // A thrown item distracts it; the player's own noise does not
                heardDistraction = (sound.type == SoundType.IMPACT);
            }
        }
    }

    // Distance plus wall muffling must be within the sound's loudness.
    // Sound travels well along an open hallway (no walls in the way).
    boolean monsterHears(SoundEvent sound) {

        int distance =
                Math.abs(monsterX - sound.x) +
                        Math.abs(monsterY - sound.y);

        int walls = wallsBetween(sound.x, sound.y, monsterX, monsterY);

        distance += WALL_MUFFLE * Math.min(walls, MAX_MUFFLE_WALLS);

        return distance <= sound.loudness;
    }

    // -------------------------------------------------
    // MONSTER AI
    // -------------------------------------------------

    void updateMonster() {

        if (gameOver || won) {
            return;
        }

        processSounds();
        updateSight();

        long now = gameNow();
        if (now < monsterStunnedUntil) return;

        long interval = switch (monsterState) {
            case CHASE, LOSE_PLAYER -> CHASE_INTERVAL;
            case INVESTIGATING -> INVESTIGATE_INTERVAL;
            default -> PATROL_INTERVAL;
        };

        if (now - lastMonsterMove < interval) {
            return;
        }

        lastMonsterMove = now;

        switch (monsterState) {
            case INVESTIGATING, LOSE_PLAYER -> goToTarget();
            case SEARCHING -> search();
            case CHASE -> chase();
            case RETURN_TO_PATROL -> returnToPatrol();
            default -> patrol();
        }

        checkCollision();
    }

    // Checked every tick: starts or ends a chase
    void updateSight() {

        if (monsterSeesPlayer()) {

            lastSeenX = playerX;
            lastSeenY = playerY;

            if (monsterState != MonsterState.CHASE) {
                monsterState = MonsterState.CHASE;
                heardDistraction = false;
                message = "IT SAW YOU! RUN!";
            }

        } else if (monsterState == MonsterState.CHASE) {

            // Lost sight: go to where the player was last seen
            monsterState = MonsterState.LOSE_PLAYER;
            targetX = lastSeenX;
            targetY = lastSeenY;
            message = "You can't see it anymore...";
        }
    }

    void chase() {

        Point next = findNextStep(
                monsterX, monsterY,
                lastSeenX, lastSeenY
        );

        if (next != null) {
            stepMonster(next);
        }
    }

    // Used by INVESTIGATING and LOSE_PLAYER: walk to the target
    void goToTarget() {

        if (targetX < 0 || targetY < 0) {
            beginReturnToPatrol();
            return;
        }

        if (monsterX == targetX && monsterY == targetY) {
            arriveAtTarget();
            return;
        }

        Point next = findNextStep(
                monsterX, monsterY,
                targetX, targetY
        );

        if (next == null) {
            // Unreachable (sealed off after a shift): search here
            arriveAtTarget();
            return;
        }

        if (!stepMonster(next)) return;

        if (monsterX == targetX && monsterY == targetY) {
            arriveAtTarget();
        }
    }

    void arriveAtTarget() {

        targetX = -1;
        targetY = -1;

        monsterState = MonsterState.SEARCHING;
        searchTicksLeft = SEARCH_TICKS;
    }

    // Linger, maybe check a nearby locker, then go back to patrolling
    void search() {

        if (tryCheckLocker()) {
            return;
        }

        searchTicksLeft--;

        if (searchTicksLeft <= 0) {
            beginReturnToPatrol();
        }
    }

    // Each nearby locker is rolled for only once per search episode
    boolean tryCheckLocker() {

        int best = -1;
        int bestDist = LOCKER_CHECK_RANGE + 1;

        for (int i = 0; i < lockers.length; i++) {

            if (lockerChecked[i]) {
                continue;
            }

            int d = Math.abs(monsterX - lockers[i][0]) +
                    Math.abs(monsterY - lockers[i][1]);

            if (d <= LOCKER_CHECK_RANGE && d < bestDist) {
                best = i;
                bestDist = d;
            }
        }

        if (best < 0) {
            return false;
        }

        lockerChecked[best] = true;

        if (random.nextDouble() >= LOCKER_CHECK_CHANCE) {
            return false;
        }

        // Walk to the locker. If the player is inside, the
        // collision check catches them when the monster arrives.
        targetX = lockers[best][0];
        targetY = lockers[best][1];
        monsterState = MonsterState.INVESTIGATING;
        heardDistraction = false; // it suspects the player, not a noise

        return true;
    }

    // Walk to the nearest waypoint, then resume patrolling
    void beginReturnToPatrol() {

        monsterState = MonsterState.RETURN_TO_PATROL;

        int bestDist = Integer.MAX_VALUE;

        for (int[] wp : waypoints) {

            int d = Math.abs(monsterX - wp[0]) +
                    Math.abs(monsterY - wp[1]);

            if (d < bestDist) {
                bestDist = d;
                patrolX = wp[0];
                patrolY = wp[1];
            }
        }
    }

    void returnToPatrol() {

        if (monsterX == patrolX && monsterY == patrolY) {
            startPatrol();
            return;
        }

        Point next = findNextStep(
                monsterX, monsterY,
                patrolX, patrolY
        );

        if (next == null) {
            startPatrol();
            return;
        }

        if (!stepMonster(next)) return;
    }

    void startPatrol() {

        monsterState = MonsterState.PATROLLING;
        Arrays.fill(lockerChecked, false);
        pickPatrolPoint();
    }

    // Walk between room centers (through hallways)
    void patrol() {

        boolean needsNewPoint =
                patrolX < 0 ||
                        (monsterX == patrolX && monsterY == patrolY);

        if (needsNewPoint) {
            pickPatrolPoint();
        }

        Point next = findNextStep(
                monsterX, monsterY,
                patrolX, patrolY
        );

        if (next == null) {
            // Unreachable; choose a different destination
            pickPatrolPoint();
            return;
        }

        if (!stepMonster(next)) return;
    }

    // Choose a random waypoint that isn't where we already are
    void pickPatrolPoint() {

        int[] wp;
        int tries = 0;

        do {
            wp = waypoints[random.nextInt(waypoints.length)];
            tries++;
        } while (tries < 20 &&
                ((wp[0] == monsterX && wp[1] == monsterY) ||
                        (wp[0] == patrolX && wp[1] == patrolY)));

        patrolX = wp[0];
        patrolY = wp[1];
    }

    Point findNextStep(int sx, int sy, int tx, int ty) {

        if (sx == tx && sy == ty) {
            return null;
        }

        Queue<Point> queue = new LinkedList<>();

        Point[][] previous = new Point[ROWS][COLS];

        boolean[][] visited = new boolean[ROWS][COLS];

        queue.add(new Point(sx, sy));
        visited[sy][sx] = true;

        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};

        while (!queue.isEmpty()) {

            Point current = queue.poll();

            if (current.x == tx && current.y == ty) {
                break;
            }

            for (int i = 0; i < 4; i++) {

                int nx = current.x + dx[i];
                int ny = current.y + dy[i];

                if (nx < 0 || ny < 0 ||
                        nx >= COLS || ny >= ROWS) {
                    continue;
                }

                if (visited[ny][nx] || terrainWall(nx, ny)) {
                    continue;
                }

                visited[ny][nx] = true;

                previous[ny][nx] = current;

                queue.add(new Point(nx, ny));
            }
        }

        if (!visited[ty][tx]) {
            return null;
        }

        // Walk backward through the path to find
        // the first step away from the monster
        Point current = new Point(tx, ty);

        while (previous[current.y][current.x] != null) {

            Point parent = previous[current.y][current.x];

            if (parent.x == sx && parent.y == sy) {
                return current;
            }

            current = parent;
        }

        return null;
    }

    void checkCollision() {

        // Reaching the exit takes priority over being caught
        if (won || gameOver) {
            return;
        }

        if (playerX == monsterX &&
                playerY == monsterY) {

            gameOver = true;
            hidden = false;
            message = "THE MONSTER CAUGHT YOU! Press R to restart.";
        }
    }

    // -------------------------------------------------
    // ROOM SHIFTING
    // -------------------------------------------------

    long nextShiftDelay() {

        if (DEBUG) {
            return DEBUG_SHIFT_INTERVAL;
        }

        return SHIFT_MIN +
                (long) (random.nextDouble() * (SHIFT_MAX - SHIFT_MIN));
    }

    // A connection is frozen (cannot change at all) if:
    //  - the player or monster is standing inside its hallway, or
    //  - the player or monster is inside either room it joins.
    // That means the occupied room keeps all its walls and doors.
    boolean isFrozen(int i, int playerSec, int monsterSec) {
        Connection c = conns[i];
        return playerSec == HALL_BASE + i || monsterSec == HALL_BASE + i
                || playerSec == roomAtSlot[c.a] || playerSec == roomAtSlot[c.b];
    }

    // Timer expired: figure out what is occupied, protect it,
    // and shift everything else.
    boolean shiftRooms() {
        return performRoomShift();
    }

    // Moves, seals or reopens every hallway that is not frozen.
    // A hallway is only sealed if all rooms stay connected.
    int reshuffleConnections(boolean[] frozen) {

        int changed = 0;

        for (int i : shuffledIndices()) {

            if (frozen[i]) {
                continue;
            }

            Connection c = conns[i];
            double roll = random.nextDouble();

            if (c.open && roll < CLOSE_CHANCE) {

                c.open = false;

                if (roomsConnected()) {
                    changed++;
                } else {
                    c.open = true; // would split the building: undo
                }

            } else if (!c.open && roll < OPEN_CHANCE) {

                c.open = true;
                c.offset = random.nextInt(c.positions());
                changed++;

            } else if (c.open && c.positions() > 1) {

                // Move the hallway to a different door position
                int n = c.positions();
                c.offset = (c.offset + 1 + random.nextInt(n - 1)) % n;
                changed++;
            }
        }

        return changed;
    }





    void updateGame() {
        if (warningSettingsOpen || viewMode == ViewMode.PAUSE || viewMode == ViewMode.MENU) {
            warningCues.update(awareness, nearbyMonsterLevel(), false, gameNow());
            repaint();
            return;
        }
        if (gameOver || won) {
            closeMap();
            updateWarningCues(gameNow());
            repaint();
            return;
        }
        long now = gameNow();
        if (viewMode == ViewMode.MAP && now >= mapClosesAt) closeMap();
        updateNoise(now);
        if (now >= nextShiftAt) shiftPending = true;
        if (shiftPending) shiftRooms();
        updateMonster();
        updateAwareness(now);
        updateWarningCues(now);
        if (gameOver || won) closeMap();
        repaint();
    }

    void restartGame() {
        resumeSimulation();
        viewMode = ViewMode.PLAYING;
        if (pausePanel != null) pausePanel.setVisible(false);
        clearAllControls();
        journal.clear();
        shiftPending = false;
        mapOpenedAt = -MAP_COOLDOWN_MS;
        mapClosesAt = 0;
        monsterStunnedUntil = 0;
        lastDoorStunAt = -DOOR_STUN_COOLDOWN_MS;
        clearMovementInput();

        buildMap(); // new hallway lengths, rooms and connections

        playerX = roomTile(0, 1, 1)[0];
        playerY = roomTile(0, 1, 1)[1];

        monsterX = waypoints[4][0];
        monsterY = waypoints[4][1];

        faceX = 1;
        faceY = 0;

        hasKey = false;
        hidden = false;
        gameOver = false;
        won = false;

        shiftNumber = 0;

        Arrays.fill(lockerChecked, false);

        soundQueue.clear();

        noise = 0;
        awareness = 0;
        awarenessTarget = 0;
        warningCues.reset();
        heardDistraction = false;
        lastNoiseText = "";
        lastSoundType = null;
        lastSoundLoudness = 0;
        lastSoundHeard = false;

        targetX = -1;
        targetY = -1;
        lastSeenX = -1;
        lastSeenY = -1;

        patrolX = -1;
        patrolY = -1;

        searchTicksLeft = 0;

        monsterState = MonsterState.PATROLLING;
        monsterFacingLeft = false;
        lastMonsterStepAt = Long.MIN_VALUE / 2;

        pickPatrolPoint();
        spawnItems();

        long now = gameNow();

        nextShiftAt = now + nextShiftDelay();
        lastNoiseUpdate = now;
        lastAwareUpdate = now;
        lastMonsterMove = now;
        lastPlayerMove = 0;
        lastPlayerStepNanos = 0;
        playerHasStepped = false;

        message = "Find the keycard and escape!";
        openOccupiedDoors();
        observeArea();

        repaint();
    }

    // -------------------------------------------------
    // GRAPHICS
    // -------------------------------------------------

    Color monsterColor() {
        return switch (monsterState) {
            case CHASE -> new Color(255, 30, 30);
            case INVESTIGATING -> new Color(255, 70, 40);
            case SEARCHING -> new Color(220, 130, 30);
            case LOSE_PLAYER -> new Color(230, 100, 60);
            case RETURN_TO_PATROL -> new Color(170, 60, 90);
            default -> new Color(190, 25, 40);
        };
    }

    void drawItem(Graphics2D g2, ItemType type, int tx, int ty) {

        int px = tx * TILE;
        int py = ty * TILE;

        g2.setColor(type.color);

        switch (type) {
            case BOTTLE -> g2.fillRect(
                    px + TILE * 3 / 8, py + TILE / 5,
                    TILE / 4, TILE * 3 / 5);
            case CAN -> g2.fillOval(
                    px + TILE / 4, py + TILE / 4,
                    TILE / 2, TILE / 2);
            case PIPE -> g2.fillRect(
                    px + TILE / 6, py + TILE * 7 / 16,
                    TILE * 2 / 3, TILE / 8);
            case BOOK -> g2.fillRect(
                    px + TILE / 4, py + TILE / 5,
                    TILE / 2, TILE * 3 / 5);
            case BOX -> g2.fillRect(
                    px + TILE / 4, py + TILE / 4,
                    TILE / 2, TILE / 2);
        }
    }

    BufferedImage currentPlayerSprite() {
        BufferedImage[] frames;
        if (faceY > 0) frames = SpriteAssets.PLAYER_FRONT;
        else if (faceY < 0) frames = SpriteAssets.PLAYER_BACK;
        else frames = faceX < 0 ? SpriteAssets.PLAYER_LEFT : SpriteAssets.PLAYER_RIGHT;
        return SpriteAssets.frame(frames, gameNow(), lastPlayerMove, 130);
    }

    BufferedImage currentMonsterSprite() {
        BufferedImage[] frames = monsterFacingLeft
                ? SpriteAssets.MONSTER_WALK_LEFT : SpriteAssets.MONSTER_WALK_RIGHT;
        if (gameNow() < monsterStunnedUntil) return frames[0];
        return SpriteAssets.frame(frames, gameNow(), lastMonsterStepAt, 180);
    }

    void drawRoomOneBackground(Graphics2D graphics) {
        if (SpriteAssets.ROOM_ONE == null) return;
        int[] origin = roomTile(0, 0, 0);
        // The artwork belongs to persistent Room 1, including after a shift.
        // Clip to uncovered floor: the background cannot reveal a hidden room.
        java.awt.geom.Area visibleFloor = new java.awt.geom.Area();
        for (int y = origin[1]; y < origin[1] + ROOM_H; y++) {
            for (int x = origin[0]; x < origin[0] + ROOM_W; x++) {
                if (!isCovered(x, y) && !terrainWall(x, y)) {
                    visibleFloor.add(new java.awt.geom.Area(new Rectangle(x * TILE, y * TILE, TILE, TILE)));
                }
            }
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.clip(visibleFloor);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(SpriteAssets.ROOM_ONE, origin[0] * TILE, origin[1] * TILE,
                ROOM_W * TILE, ROOM_H * TILE, null);
        g.dispose();
    }

    @Override
    protected void paintComponent(Graphics g) {

        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();

        double scaleX = (double) getWidth() / 840;
        double scaleY = (double) getHeight() / 688;

        double scale = Math.min(scaleX, scaleY);

        double offsetX = (getWidth() - (840 * scale)) / 2;
        double offsetY = (getHeight() - (688 * scale)) / 2;

        g2.translate(offsetX, offsetY);
        g2.scale(scale, scale);

        updateCeilings();

        int cur = sectionAt(playerX, playerY);

        // Draw the whole building. Covered sections show a ceiling instead
        // of their floor, walls and doorways, so the inside stays hidden.
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {

                int px = x * TILE;
                int py = y * TILE;

                if (isCovered(x, y)) {

                    int sec = section[y][x];

                    g2.setColor(sec < HALL_BASE
                            ? ROOM_CEILING_COLOR : HALL_CEILING_COLOR);

                    g2.fillRect(px, py, TILE, TILE);

                    // Large panels: a seam only on every 2nd tile line
                    g2.setColor(CEILING_SEAM_COLOR);

                    if (x % CEILING_PANEL == 0) {
                        g2.fillRect(px, py, 1, TILE);
                    }
                    if (y % CEILING_PANEL == 0) {
                        g2.fillRect(px, py, TILE, 1);
                    }

                    // Rivet where four panels meet
                    if (x % CEILING_PANEL == 0 && y % CEILING_PANEL == 0) {
                        g2.setColor(CEILING_RIVET_COLOR);
                        g2.fillRect(px + 2, py + 2, 3, 3);
                    }

                    // Dark outline where the roof meets another section
                    g2.setColor(CEILING_EDGE_COLOR);

                    if (roofEdge(x, y - 1, sec)) {
                        g2.fillRect(px, py, TILE, 3);
                    }
                    if (roofEdge(x, y + 1, sec)) {
                        g2.fillRect(px, py + TILE - 3, TILE, 3);
                    }
                    if (roofEdge(x - 1, y, sec)) {
                        g2.fillRect(px, py, 3, TILE);
                    }
                    if (roofEdge(x + 1, y, sec)) {
                        g2.fillRect(px + TILE - 3, py, 3, TILE);
                    }

                    continue;
                }

                char t = map[y][x];

                if (t == '#') {
                    g2.setColor(WALL_COLOR);
                } else if (t == 'h') {
                    g2.setColor(HALL_COLOR);
                } else {
                    g2.setColor(FLOOR_COLOR);
                }

                g2.fillRect(px, py, TILE, TILE);

                g2.setColor(GRID_COLOR);

                g2.drawRect(px, py, TILE, TILE);
            }
        }

        drawRoomOneBackground(g2);

        // Draw lockers
        g2.setColor(LOCKER_COLOR);

        for (int[] l : lockers) {

            if (!canSeeTile(l[0], l[1])) {
                continue;
            }

            g2.fillRect(
                    l[0] * TILE + TILE / 8,
                    l[1] * TILE + TILE / 16,
                    TILE - TILE / 4, TILE - TILE / 8
            );
        }

        // Draw keycard
        if (!hasKey && canSeeTile(keyX, keyY)) {

            g2.setColor(Color.YELLOW);

            g2.fillRect(
                    keyX * TILE + TILE / 3,
                    keyY * TILE + TILE / 3,
                    TILE / 3, TILE / 3
            );
        }

        // Draw exit
        if (canSeeTile(exitX, exitY)) {

            g2.setColor(hasKey ? Color.GREEN : Color.RED);

            g2.fillRect(
                    exitX * TILE + 5,
                    exitY * TILE + 5,
                    TILE - 10,
                    TILE - 10
            );
        }

        // Draw items lying on the floor
        for (Item it : items) {
            if (canSeeTile(it.x, it.y)) {
                drawItem(g2, it.type, it.x, it.y);
            }
        }

        drawDoorsAndRoomLabels(g2);

        // Draw monster (only if the player can actually see it)
        // Normal play: only when its tile has no ceiling and it is in view.
        if (canSeeTile(monsterX, monsterY) && playerSeesMonster()) {

            // Keep its feet on the tile and animate successful steps only.
            BufferedImage monsterSprite = currentMonsterSprite();

            SpriteAssets.draw(g2, monsterSprite,
                    monsterX * TILE + TILE / 2,
                    monsterY * TILE + TILE,
                    TILE + 14, TILE * 2,
                    1.0f, false);

            if (monsterSprite == null) { // sprite missing: old placeholder
                g2.setColor(monsterColor());
                g2.fillOval(monsterX * TILE + 3, monsterY * TILE + 3,
                        TILE - 6, TILE - 6);
            }
        }

        drawMonsterStun(g2);

        // Both side directions have their own supplied artwork.
        BufferedImage playerSprite = currentPlayerSprite();

        SpriteAssets.draw(g2, playerSprite,
                playerX * TILE + TILE / 2,
                playerY * TILE + TILE,
                TILE - 3, TILE,
                hidden ? 0.38f : 1.0f, false);

        if (playerSprite == null) { // sprite missing: old placeholder
            g2.setColor(hidden
                    ? PLAYER_COLOR.darker().darker() : PLAYER_COLOR);
            g2.fillOval(playerX * TILE + 5, playerY * TILE + 5,
                    TILE - 10, TILE - 10);
        }

        // Held item shown as a small icon on the player
        if (held != null) {

            g2.setColor(held.type.color);

            g2.fillRect(
                    playerX * TILE + TILE - 10,
                    playerY * TILE + 1,
                    9, 9
            );
        }

        // Warning edges are confined to the playfield; the HUD stays readable.
        warningCues.draw(g2, COLS * TILE, ROWS * TILE);

        // Status panel
        int panelY = ROWS * TILE;

        g2.setFont(INFO_FONT);

        g2.setColor(Color.BLACK);

        g2.fillRect(
                0, panelY,
                COLS * TILE, PANEL_H
        );

        g2.setColor(Color.WHITE);

        g2.drawString(
                "Keycard: " + (hasKey ? "YES" : "NO")
                        + "   Status: " + (hidden ? "HIDDEN" : "EXPOSED"),
                10, panelY + 16
        );

        g2.drawString(
                "Holding: " + (held == null ? "nothing" : held.type.label),
                10, panelY + 32
        );

        // Two separate meters: what YOU do, and what the MONSTER knows
        drawSoundMeter(g2, 10, panelY + 52);
        drawAwarenessMeter(g2, 10, panelY + 78);

        g2.setColor(Color.WHITE); // meters changed the color; restore it

        g2.drawString(
                message,
                250, panelY + 18
        );

        g2.drawString(
                "WASD/Arrows: Move | Shift+Move: Run",
                250, panelY + 36
        );

        g2.drawString(
                "E: Grab | Q: Throw | F: Hide | R: Restart | P: Settings",
                250, panelY + 54
        );

        g2.drawString("Space: Door | M: " + (mapCooldownRemaining() == 0 ? "Map ready"
                : "Map " + (long) Math.ceil(mapCooldownRemaining() / 1000.0) + "s")
                + " | Esc: Pause", 250, panelY + 98);

        // Interaction prompt (only when something can be done)
        String prompt = interactionPrompt();

        if (!prompt.isEmpty()) {
            g2.setFont(PROMPT_FONT);
            g2.setColor(Color.YELLOW);
            g2.drawString(prompt, 250, panelY + 80);
            g2.setFont(INFO_FONT);
        }

        if (DEBUG) {

            long remaining = Math.max(
                    0, nextShiftAt - gameNow()
            );

            long secs = remaining / 1000;

            int protectedSec = cur;
            int monsterSec = sectionAt(monsterX, monsterY);

            g2.setColor(new Color(150, 220, 150));

            g2.drawString(
                    "[DEBUG] Current: " + sectionName(cur),
                    560, panelY + 14
            );

            g2.drawString(
                    "Protected: " + sectionName(protectedSec),
                    560, panelY + 28
            );

            g2.drawString(
                    String.format("Shift timer: %02d:%02d",
                            secs / 60, secs % 60),
                    560, panelY + 42
            );

            g2.drawString(
                    "Ceiling off: " + sectionName(cur),
                    560, panelY + 56
            );

            g2.drawString(
                    String.format("Noise %.2f last:%s(%d) heard:%s",
                            noise,
                            lastSoundType == null ? "-" : lastSoundType.name(),
                            lastSoundLoudness,
                            lastSoundHeard ? "yes" : "no"),
                    560, panelY + 70
            );

            g2.drawString(
                    String.format("Monster: %s aware %.2f>%.2f",
                            monsterState.label, awareness, awarenessTarget),
                    560, panelY + 84
            );

            g2.drawString(
                    "[M] remembered map",
                    560, panelY + 98
            );
        }

        // End screens
        if (gameOver || won) {

            g2.setColor(OVERLAY_COLOR);

            g2.fillRect(
                    0, 0,
                    COLS * TILE, ROWS * TILE
            );

            g2.setFont(BIG_FONT);

            g2.setColor(won ? Color.GREEN : Color.RED);

            String title = won ? "YOU ESCAPED!" : "GAME OVER";

            FontMetrics fm = g2.getFontMetrics();

            int textX =
                    (COLS * TILE - fm.stringWidth(title)) / 2;

            g2.drawString(title, textX, ROWS * TILE / 2);

            g2.setFont(SMALL_FONT);

            g2.setColor(Color.WHITE);

            String again = "Press R to play again";

            fm = g2.getFontMetrics();

            g2.drawString(
                    again,
                    (COLS * TILE - fm.stringWidth(again)) / 2,
                    ROWS * TILE / 2 + 35
            );
        }
        if (viewMode == ViewMode.MAP) drawMemoryMap(g2);
        else if (viewMode == ViewMode.PAUSE) {
            g2.setColor(new Color(0, 0, 0, 205));
            g2.fillRect(0, 0, COLS * TILE, ROWS * TILE + PANEL_H);
        }
        g2.dispose();
    }

    // -------------------------------------------------
    // RUN MODES, PERSISTENT ROOMS, DOORS, AND REMEMBERED MAP
    // -------------------------------------------------

    enum ViewMode { PLAYING, MAP, PAUSE, MENU }

    long gameNow() {
        return (clockPaused ? pausedWallAt : wallClock.getAsLong()) - pausedMillis;
    }

    long movementNow() {
        return (clockPaused ? pausedMovementAt : movementClock.getAsLong()) - pausedNanos;
    }

    void pauseSimulation() {
        if (clockPaused) return;
        pausedWallAt = wallClock.getAsLong();
        pausedMovementAt = movementClock.getAsLong();
        clockPaused = true;
        suppressHeldMovement();
        warningCues.update(awareness, nearbyMonsterLevel(), false, gameNow());
    }

    void resumeSimulation() {
        if (!clockPaused) return;
        pausedMillis += Math.max(0, wallClock.getAsLong() - pausedWallAt);
        pausedNanos += Math.max(0, movementClock.getAsLong() - pausedMovementAt);
        clockPaused = false;
    }

    boolean inputLocked() {
        return viewMode != ViewMode.PLAYING || warningSettingsOpen || gameOver || won;
    }

    void suppressHeldMovement() {
        suppressedMovementKeys.addAll(heldMovementKeys.keySet());
        clearMovementInput();
    }

    void clearAllControls() {
        clearMovementInput();
        suppressedMovementKeys.clear();
        heldActionKeys.clear();
        mapKeyHeld = escapeKeyHeld = false;
    }

    void bindGameplayAction(int code, String name, Runnable action, boolean allowEnded) {
        bindAction(KeyStroke.getKeyStroke(code, 0, false), name, () -> {
            if (!heldActionKeys.add(code)) return;
            if (viewMode != ViewMode.PLAYING || warningSettingsOpen) return;
            if (!allowEnded && (gameOver || won)) return;
            action.run();
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(code, InputEvent.SHIFT_DOWN_MASK, false), name);
        bindModifierVariants(code, true, name + "-released", () -> heldActionKeys.remove(code));
    }

    void toggleMap() {
        if (viewMode == ViewMode.MAP) {
            closeMap();
        } else if (!inputLocked()) {
            long now = gameNow();
            if (now - mapOpenedAt < MAP_COOLDOWN_MS) {
                message = "Map recharges in " + (long) Math.ceil(mapCooldownRemaining() / 1000.0) + "s.";
                return;
            }
            mapOpenedAt = now;
            mapClosesAt = now + MAP_DURATION_MS;
            viewMode = ViewMode.MAP;
            suppressHeldMovement();
        }
        repaint();
    }

    long mapCooldownRemaining() {
        return Math.max(0, MAP_COOLDOWN_MS - (gameNow() - mapOpenedAt));
    }

    void closeMap() {
        if (viewMode != ViewMode.MAP) return;
        viewMode = ViewMode.PLAYING;
        suppressHeldMovement();
        repaint();
    }

    void handleEscape() {
        if (warningSettingsOpen) return;
        if (viewMode == ViewMode.MAP) closeMap();
        else if (viewMode == ViewMode.PAUSE) resumeFromPause();
        else if (viewMode == ViewMode.PLAYING) {
            viewMode = ViewMode.PAUSE;
            pauseSimulation();
            movementTimer.stop();
            resumeButton.setEnabled(!gameOver && !won);
            pausePanel.setVisible(true);
            revalidate();
            repaint();
        }
    }

    void resumeFromPause() {
        if (viewMode != ViewMode.PAUSE) return;
        viewMode = ViewMode.PLAYING;
        pausePanel.setVisible(false);
        resumeSimulation();
        clearMovementInput();
        if (isDisplayable()) movementTimer.start();
        requestFocusInWindow();
        repaint();
    }

    void initPausePanel() {
        setLayout(new GridBagLayout());
        pausePanel = new JPanel();
        pausePanel.setLayout(new BoxLayout(pausePanel, BoxLayout.Y_AXIS));
        pausePanel.setBackground(new Color(12, 10, 17));
        pausePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(170, 25, 38), 2),
                BorderFactory.createEmptyBorder(24, 32, 24, 32)));
        JLabel title = new JLabel("DEAD SIGNAL — PAUSED");
        title.setForeground(new Color(210, 65, 70));
        title.setFont(new Font("Monospaced", Font.BOLD, 20));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        pausePanel.add(title);
        pausePanel.add(Box.createVerticalStrut(20));
        resumeButton = pauseButton("RESUME", this::resumeFromPause);
        pausePanel.add(resumeButton);
        pausePanel.add(Box.createVerticalStrut(12));
        pausePanel.add(pauseButton("RETURN TO MAIN MENU", () -> {
            if (ownerSession != null) ownerSession.showMenu();
        }));
        pausePanel.add(Box.createVerticalStrut(12));
        pausePanel.add(pauseButton("EXIT GAME", () -> {
            if (ownerSession != null) ownerSession.requestExit();
        }));
        pausePanel.setVisible(false);
        add(pausePanel);
    }

    JButton pauseButton(String label, Runnable action) {
        JButton button = new JButton(label);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setBackground(new Color(25, 18, 23));
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Monospaced", Font.BOLD, 14));
        button.setMaximumSize(new Dimension(290, 44));
        button.setPreferredSize(new Dimension(290, 44));
        button.setBorder(BorderFactory.createLineBorder(new Color(170, 25, 38), 2));
        button.addActionListener(e -> action.run());
        return button;
    }

    void suspendForMenu() {
        pauseSimulation();
        viewMode = ViewMode.MENU;
        pausePanel.setVisible(false);
        movementTimer.stop();
        gameTimer.stop();
        warningCues.audio.close();
        clearAllControls();
    }

    void continueRun() {
        viewMode = ViewMode.PLAYING;
        pausePanel.setVisible(false);
        resumeSimulation();
        clearAllControls();
    }

    static final class GameSession {
        final JFrame frame;
        Main run;

        GameSession(JFrame frame) { this.frame = frame; }

        boolean canContinue() { return run != null && !run.gameOver && !run.won; }

        void showMenu() {
            if (run != null) run.suspendForMenu();
            if (frame != null) show(new MainMenuPanel(frame, this));
        }

        void startNewGame(Font menuFont) {
            if (canContinue() && frame != null && JOptionPane.showConfirmDialog(frame,
                    "Replace your current run with a new game?", "NEW GAME",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            if (run != null) run.suspendForMenu();
            Runnable beginRun = () -> {
                run = new Main();
                run.ownerSession = this;
                if (frame != null) show(run);
            };
            if (frame == null) beginRun.run();
            else IntroVideoPanel.showBeforeGame(frame, menuFont, beginRun);
        }

        void continueGame() {
            if (!canContinue()) return;
            run.continueRun();
            if (frame != null) show(run);
        }

        void show(JPanel panel) {
            frame.setContentPane(panel);
            frame.revalidate();
            frame.repaint();
            panel.requestFocusInWindow();
        }

        void requestExit() {
            if (frame == null) return;
            if (run != null) {
                if (run.viewMode == ViewMode.MAP) run.closeMap();
                if (run.viewMode == ViewMode.PLAYING) run.handleEscape();
            }
            if (JOptionPane.showConfirmDialog(frame,
                    "Exit the game? The in-memory run will be lost.", "EXIT GAME",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) System.exit(0);
        }
    }

    static final class Door {
        final int roomId;
        final int side;
        boolean open = true;
        boolean active;
        boolean horizontalCorridor;
        int x, y;
        long glowUntil;

        Door(int roomId, int side) { this.roomId = roomId; this.side = side; }
    }

    void initRoomIdentities() {
        for (int room = 0; room < ROOM_COUNT; room++) {
            roomAtSlot[room] = slotOfRoom[room] = room;
            for (int side = 0; side < 4; side++) roomDoors[room][side] = new Door(room, side);
        }
    }

    void rebuildDoors() {
        for (Door[] doors : roomDoors) for (Door door : doors) door.active = false;
        for (Connection c : conns) {
            if (!c.open) continue;
            int col = c.a % ROOM_COLS, row = c.a / ROOM_COLS;
            Door a = roomDoors[roomAtSlot[c.a]][c.horizontal ? 1 : 2];
            Door b = roomDoors[roomAtSlot[c.b]][c.horizontal ? 3 : 0];
            a.active = b.active = true;
            a.horizontalCorridor = b.horizontalCorridor = c.horizontal;
            if (c.horizontal) {
                a.x = roomX0[col] + ROOM_W;
                b.x = roomX0[col + 1] - 1;
                a.y = b.y = roomY0[row] + c.offset;
            } else {
                a.y = roomY0[row] + ROOM_H;
                b.y = roomY0[row + 1] - 1;
                a.x = b.x = roomX0[col] + c.offset;
            }
        }
        forcingDoor = null;
        doorForceStartedAt = -1;
    }

    Door doorAt(int x, int y) {
        for (Door[] doors : roomDoors) for (Door door : doors) {
            if (door != null && door.active && door.x == x && door.y == y) return door;
        }
        return null;
    }

    boolean terrainWall(int x, int y) {
        return x < 0 || y < 0 || x >= COLS || y >= ROWS || map[y][x] == '#';
    }

    Door facingDoor() {
        Door door = doorAt(playerX + faceX, playerY + faceY);
        return door != null ? door : doorAt(playerX, playerY);
    }

    void toggleDoor() {
        if (inputLocked() || hidden) return;
        Door door = facingDoor();
        if (door == null) { message = "Face an adjacent door and press Space."; return; }
        if (door.open && ((playerX == door.x && playerY == door.y)
                || (monsterX == door.x && monsterY == door.y))) {
            message = "The doorway is occupied.";
            return;
        }
        door.open = !door.open;
        warningCues.audio.doorSound(!door.open);
        emitSound(SoundType.DOOR, door.x, door.y, door.open ? 4 : 9);
        message = door.open ? "You open the door." : "You close the door.";
        long now = gameNow();
        if (!door.open && Math.abs(monsterX - door.x) + Math.abs(monsterY - door.y) == 1
                && now - lastDoorStunAt >= DOOR_STUN_COOLDOWN_MS) {
            monsterStunnedUntil = now + DOOR_STUN_MS;
            lastDoorStunAt = now;
            door.glowUntil = monsterStunnedUntil;
            forcingDoor = null;
            doorForceStartedAt = -1;
            message = "DOOR SLAM! The monster staggers.";
        }
        repaint();
    }

    boolean stepMonster(Point next) {
        if (next == null || gameNow() < monsterStunnedUntil) return false;
        Door door = doorAt(next.x, next.y);
        if (door != null && !door.open) {
            if (forcingDoor != door) {
                forcingDoor = door;
                doorForceStartedAt = gameNow();
                if (canSeeTile(door.x, door.y)) message = "The monster is forcing the door...";
            }
            if (gameNow() - doorForceStartedAt >= DOOR_FORCE_MS) {
                door.open = true;
                warningCues.audio.doorSound(true);
                forcingDoor = null;
                doorForceStartedAt = -1;
                if (canSeeTile(door.x, door.y)) message = "The monster forces the door open!";
            }
            return false;
        }
        forcingDoor = null;
        doorForceStartedAt = -1;
        if (next.x != monsterX) monsterFacingLeft = next.x < monsterX;
        lastMonsterStepAt = gameNow();
        monsterX = next.x;
        monsterY = next.y;
        return true;
    }

    record RoomAnchor(int room, int x, int y) { }
    record ItemAnchor(Item item, RoomAnchor anchor) { }
    record SoundAnchor(SoundEvent sound, RoomAnchor anchor) { }

    RoomAnchor anchorAt(int x, int y) {
        if (x < 0 || y < 0) return null;
        int room = sectionAt(x, y);
        if (room >= 0 && room < ROOM_COUNT) {
            int[] origin = roomTile(room, 0, 0);
            return new RoomAnchor(room, x - origin[0], y - origin[1]);
        }
        return new RoomAnchor(-1, x, y);
    }

    Point resolveAnchor(RoomAnchor anchor) {
        if (anchor == null) return new Point(-1, -1);
        if (anchor.room() >= 0) {
            int[] point = roomTile(anchor.room(), anchor.x(), anchor.y());
            return new Point(point[0], point[1]);
        }
        return terrainWall(anchor.x(), anchor.y())
                ? nearestFloor(anchor.x(), anchor.y(), -1) : new Point(anchor.x(), anchor.y());
    }

    Point nearestFloor(int x, int y, int excludedRoom) {
        Point best = null;
        int distance = Integer.MAX_VALUE;
        for (int row = 0; row < ROWS; row++) for (int col = 0; col < COLS; col++) {
            int d = Math.abs(col - x) + Math.abs(row - y);
            if (!terrainWall(col, row) && sectionAt(col, row) != excludedRoom && d < distance) {
                best = new Point(col, row);
                distance = d;
            }
        }
        if (best == null) throw new IllegalStateException("No safe floor in connected facility");
        return best;
    }

    boolean performRoomShift() {
        int playerRoom = sectionAt(playerX, playerY);
        if (playerRoom < 0 || playerRoom >= ROOM_COUNT) { shiftPending = true; return false; }
        int monsterSection = sectionAt(monsterX, monsterY);
        boolean[] frozen = new boolean[conns.length];
        for (int i = 0; i < conns.length; i++) frozen[i] = isFrozen(i, playerRoom, monsterSection);
        ArrayList<ItemAnchor> itemAnchors = new ArrayList<>();
        for (Item item : items) itemAnchors.add(new ItemAnchor(item, anchorAt(item.x, item.y)));
        RoomAnchor[] lockerAnchors = new RoomAnchor[lockers.length];
        for (int i = 0; i < lockers.length; i++) lockerAnchors[i] = anchorAt(lockers[i][0], lockers[i][1]);
        RoomAnchor monster = anchorAt(monsterX, monsterY);
        RoomAnchor key = anchorAt(keyX, keyY), exit = anchorAt(exitX, exitY);
        RoomAnchor target = anchorAt(targetX, targetY), lastSeen = anchorAt(lastSeenX, lastSeenY);
        RoomAnchor patrol = anchorAt(patrolX, patrolY);
        ArrayList<SoundAnchor> sounds = new ArrayList<>();
        for (SoundEvent sound : soundQueue) sounds.add(new SoundAnchor(sound, anchorAt(sound.x, sound.y)));

        ArrayList<Integer> movable = new ArrayList<>();
        for (int room = 0; room < ROOM_COUNT; room++) if (room != playerRoom) movable.add(room);
        Collections.shuffle(movable, random);
        int[] oldSlots = slotOfRoom.clone();
        for (int i = 0; i < movable.size(); i++) {
            slotOfRoom[movable.get(i)] = oldSlots[movable.get((i + 1) % movable.size())];
        }
        for (int room = 0; room < ROOM_COUNT; room++) roomAtSlot[slotOfRoom[room]] = room;
        reshuffleConnections(frozen);
        initSections();
        applyLayout();
        rebuildDoors();
        for (int room = 0; room < ROOM_COUNT; room++) waypoints[room] = roomTile(room, ROOM_W / 2, ROOM_H / 2);
        for (int i = 0; i < lockers.length; i++) {
            Point p = resolveAnchor(lockerAnchors[i]); lockers[i][0] = p.x; lockers[i][1] = p.y;
        }
        Point p = resolveAnchor(monster); monsterX = p.x; monsterY = p.y;
        p = resolveAnchor(key); keyX = p.x; keyY = p.y;
        p = resolveAnchor(exit); exitX = p.x; exitY = p.y;
        p = resolveAnchor(target); targetX = p.x; targetY = p.y;
        p = resolveAnchor(lastSeen); lastSeenX = p.x; lastSeenY = p.y;
        p = resolveAnchor(patrol); patrolX = p.x; patrolY = p.y;
        for (ItemAnchor entry : itemAnchors) {
            RoomAnchor anchor = entry.anchor();
            Point destination = anchor.room() < 0 && terrainWall(anchor.x(), anchor.y())
                    ? nearestFloor(anchor.x(), anchor.y(), playerRoom) : resolveAnchor(anchor);
            entry.item().x = destination.x; entry.item().y = destination.y;
        }
        soundQueue.clear();
        for (SoundAnchor entry : sounds) {
            p = resolveAnchor(entry.anchor());
            SoundEvent sound = entry.sound();
            soundQueue.add(new SoundEvent(sound.type, p.x, p.y, sound.loudness, sound.byPlayer, sound.time));
        }
        openOccupiedDoors();
        shiftNumber++;
        shiftPending = false;
        nextShiftAt = gameNow() + nextShiftDelay();
        observeArea();
        message = "Metal groans. Other rooms have exchanged places...";
        return true;
    }

    void openOccupiedDoors() {
        for (Door[] doors : roomDoors) for (Door door : doors) {
            if (!door.active) continue;
            boolean occupied = (playerX == door.x && playerY == door.y)
                    || (monsterX == door.x && monsterY == door.y) || itemAt(door.x, door.y);
            if (occupied) door.open = true;
        }
    }

    record RoomMemory(int roomId, int seenShift) { }
    record LinkMemory(int a, int b, boolean open, int seenShift) { }

    // Only player observations live here. There is deliberately no enemy data.
    static final class MapJournal {
        final RoomMemory[] rooms = new RoomMemory[ROOM_COUNT];
        final LinkMemory[] links = new LinkMemory[CONN_COUNT];

        void clear() { Arrays.fill(rooms, null); Arrays.fill(links, null); }

        void observeRoom(int roomId, int slot, Connection[] connections, int shift) {
            for (int i = 0; i < rooms.length; i++) {
                if (rooms[i] != null && rooms[i].roomId() == roomId) rooms[i] = null;
            }
            rooms[slot] = new RoomMemory(roomId, shift);
            for (int i = 0; i < connections.length; i++) {
                Connection c = connections[i];
                if (c.a == slot || c.b == slot) observeLink(i, c, shift);
            }
        }

        void observeLink(int index, Connection c, int shift) {
            links[index] = new LinkMemory(c.a, c.b, c.open, shift);
        }

        boolean stale(int shift) {
            for (RoomMemory room : rooms) if (room != null && room.seenShift() < shift) return true;
            for (LinkMemory link : links) if (link != null && link.seenShift() < shift) return true;
            return false;
        }
    }

    void observeArea() {
        int area = sectionAt(playerX, playerY);
        if (area >= 0 && area < ROOM_COUNT) journal.observeRoom(area, slotOfRoom[area], conns, shiftNumber);
        else if (area >= HALL_BASE && area < HALL_BASE + CONN_COUNT) {
            journal.observeLink(area - HALL_BASE, conns[area - HALL_BASE], shiftNumber);
        }
    }

    void drawMemoryMap(Graphics2D graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(new Color(7, 9, 15));
        g.fillRect(0, 0, COLS * TILE, ROWS * TILE + PANEL_H);
        g.setFont(new Font("Monospaced", Font.BOLD, 24));
        g.setColor(new Color(205, 65, 70));
        g.drawString("FACILITY MAP — LAST KNOWN", 55, 65);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(185, 190, 195));
        g.drawString(String.format("Closes in %.1fs   |   M / Esc: close", Math.max(0, mapClosesAt - gameNow()) / 1000.0), 55, 100);
        g.setColor(new Color(165, 150, 115));
        g.drawString(journal.stale(shiftNumber) ? "Routes may have shifted. Revisit rooms to update them."
                : "Only explored rooms are recorded.", 55, 130);
        for (LinkMemory link : journal.links) {
            if (link == null || journal.rooms[link.a()] == null || journal.rooms[link.b()] == null) continue;
            int ax = 160 + link.a() % ROOM_COLS * 255, ay = 220 + link.a() / ROOM_COLS * 145;
            int bx = 160 + link.b() % ROOM_COLS * 255, by = 220 + link.b() / ROOM_COLS * 145;
            g.setColor(link.open() ? new Color(130, 140, 140) : new Color(95, 65, 65));
            g.setStroke(link.open() ? new BasicStroke(5) : new BasicStroke(3, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10, new float[] {7, 6}, 0));
            g.drawLine(ax, ay, bx, by);
        }
        int playerArea = sectionAt(playerX, playerY);
        for (int slot = 0; slot < ROOM_COUNT; slot++) {
            RoomMemory memory = journal.rooms[slot];
            if (memory == null) continue;
            int x = 85 + slot % ROOM_COLS * 255, y = 180 + slot / ROOM_COLS * 145;
            boolean here = memory.roomId() == playerArea;
            g.setColor(new Color(20, 25, 32)); g.fillRoundRect(x, y, 150, 80, 12, 12);
            g.setColor(here ? PLAYER_COLOR : new Color(175, 150, 105));
            g.setStroke(new BasicStroke(here ? 3 : 2)); g.drawRoundRect(x, y, 150, 80, 12, 12);
            g.setFont(new Font("Monospaced", Font.BOLD, 19));
            g.drawString("ROOM " + (memory.roomId() + 1), x + 36, y + 34);
            if (here) { g.setFont(INFO_FONT); g.drawString("YOU ARE HERE", x + 35, y + 58); }
        }
        if (playerArea >= HALL_BASE) {
            g.setColor(PLAYER_COLOR); g.setFont(SMALL_FONT);
            g.drawString("You are in a hallway.", 55, 625);
        }
        g.setColor(new Color(180, 180, 185)); g.setFont(INFO_FONT);
        g.drawString("Movement and interactions disabled. The facility is still active.", 55, 660);
        g.dispose();
    }

    void drawDoorsAndRoomLabels(Graphics2D g) {
        g.setFont(new Font("Monospaced", Font.BOLD, 10));
        for (int room = 0; room < ROOM_COUNT; room++) {
            int[] origin = roomTile(room, 0, 0);
            if (!canSeeTile(origin[0], origin[1])) continue;
            g.setColor(new Color(175, 155, 115));
            g.drawString("ROOM " + (room + 1), origin[0] * TILE + 5, origin[1] * TILE + 12);
        }
        for (Door[] doors : roomDoors) for (Door door : doors) {
            if (!door.active || !canSeeTile(door.x, door.y)) continue;
            int x = door.x * TILE, y = door.y * TILE;
            g.setColor(gameNow() < door.glowUntil ? new Color(255, 225, 135)
                    : door.open ? new Color(100, 190, 160) : new Color(225, 170, 70));
            g.setStroke(new BasicStroke(3)); g.drawRect(x + 2, y + 2, TILE - 4, TILE - 4);
            if (door.horizontalCorridor) g.fillRect(x + (door.open ? 3 : TILE / 2 - 4), y + 3, 8, TILE - 6);
            else g.fillRect(x + 3, y + (door.open ? 3 : TILE / 2 - 4), TILE - 6, 8);
            g.setColor(Color.WHITE); g.fillOval(x + TILE / 2 - 1, y + TILE / 2 - 1, 3, 3);
        }
        g.setStroke(new BasicStroke(1));
    }

    void drawMonsterStun(Graphics2D g) {
        if (gameNow() >= monsterStunnedUntil || !canSeeTile(monsterX, monsterY) || !playerSeesMonster()) return;
        g.setColor(new Color(255, 225, 135)); g.setFont(PROMPT_FONT);
        g.drawString("* * *", monsterX * TILE - 3, monsterY * TILE - 3);
    }


    // -------------------------------------------------
    // WARNING AUDIO AND VISUALS (player feedback only)
    // -------------------------------------------------

    static final class WarningSettings {
        volatile int volume = 55;
        volatile boolean reducedEffects = false;
    }

    static void showWarningSettings(Component parent) {
        JSlider volume = new JSlider(0, 100, WARNING_SETTINGS.volume);
        volume.setMajorTickSpacing(25);
        volume.setPaintTicks(true);
        volume.setPaintLabels(true);
        volume.setToolTipText("Zero mutes warning audio.");
        JCheckBox reduced = new JCheckBox("Reduced visual effects",
                WARNING_SETTINGS.reducedEffects);
        JPanel options = new JPanel(new GridLayout(0, 1, 0, 8));
        options.add(new JLabel("Warning volume (0 = muted)"));
        options.add(volume);
        options.add(reduced);
        options.add(new JLabel("A quiet drone also signals a nearby monster."));
        int answer = JOptionPane.showConfirmDialog(parent, options, "Warning Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            WARNING_SETTINGS.volume = volume.getValue();
            WARNING_SETTINGS.reducedEffects = reduced.isSelected();
        }
    }

    record CueMix(double awareness, double proximity, double volume,
                  boolean active, long sting, long generation) { }

    static final class WarningCues {
        final WarningAudio audio = new WarningAudio();
        double awareness;
        double beatPhase;
        boolean active;
        boolean highThreat;
        long lastUpdate;
        long lastSting;
        long sting;
        long generation;

        static double clamp(double value) {
            return Math.max(0, Math.min(1, value));
        }

        static double beat(double phase) {
            if (phase < 0.12) return Math.pow(Math.sin(Math.PI * phase / 0.12), 2);
            if (phase >= 0.18 && phase < 0.28) {
                return 0.65 * Math.pow(Math.sin(Math.PI * (phase - 0.18) / 0.10), 2);
            }
            return 0;
        }

        void update(double level, double proximity, boolean enabled, long now) {
            awareness = clamp(level);
            active = enabled;
            double seconds = lastUpdate == 0 ? 0
                    : Math.max(0, Math.min(0.2, (now - lastUpdate) / 1000.0));
            lastUpdate = now;
            beatPhase = (beatPhase + seconds * (60 + 80 * awareness) / 60) % 1;
            if (awareness < 0.55) highThreat = false;
            if (awareness >= 0.70 && !highThreat) {
                if (enabled && now - lastSting >= CUE_STING_COOLDOWN_MS) {
                    sting++;
                    lastSting = now;
                }
                highThreat = true;
            }
            audio.target = new CueMix(awareness, clamp(proximity),
                    WARNING_SETTINGS.volume / 100.0, enabled, sting, generation);
        }

        void reset() {
            awareness = 0;
            beatPhase = 0;
            active = false;
            highThreat = false;
            lastUpdate = 0;
            lastSting = 0;
            generation++;
            audio.target = new CueMix(0, 0, 0, false, sting, generation);
        }

        double pulse() {
            return audio.available() ? audio.pulse() : beat(beatPhase);
        }

        void draw(Graphics2D graphics, int width, int height) {
            if (!active || awareness < 0.08) return;
            boolean reduced = WARNING_SETTINGS.reducedEffects;
            double pulse = pulse();
            double strength = (awareness - 0.08) / 0.92;
            int alpha = (int) Math.round(CUE_MAX_EDGE_ALPHA * strength
                    * (reduced ? 0.45 : 0.75 + 0.25 * pulse));
            int edge = Math.max(1, Math.min(width, height) / 6);
            Color rim = new Color(150, 8, 25, alpha);
            Color clear = new Color(150, 8, 25, 0);
            Graphics2D g = (Graphics2D) graphics.create();
            g.clipRect(0, 0, width, height);
            g.setPaint(new GradientPaint(0, 0, rim, edge, 0, clear));
            g.fillRect(0, 0, edge, height);
            g.setPaint(new GradientPaint(width, 0, rim, width - edge, 0, clear));
            g.fillRect(width - edge, 0, edge, height);
            g.setPaint(new GradientPaint(0, 0, rim, 0, edge, clear));
            g.fillRect(0, 0, width, edge);
            g.setPaint(new GradientPaint(0, height, rim, 0, height - edge, clear));
            g.fillRect(0, height - edge, width, edge);
            g.dispose();
        }
    }

    // Original procedural soundscape: no downloaded audio or additional libraries.
    static final class CueSynth {
        private final Random random = new Random(90210);
        private double time;
        private double phase;
        private double awareness;
        private double proximity;
        private double gain;
        private double hiss;
        private double stingTime = -1;
        private long seenSting = -1;
        private long seenGeneration = -1;
        private long seenDoor;
        private double doorTime = -1;
        private boolean doorClosed;
        double pulse;

        void render(byte[] pcm, CueMix mix) {
            render(pcm, mix, 0, false);
        }

        void render(byte[] pcm, CueMix mix, long doorSerial, boolean closed) {
            if (mix.generation() != seenGeneration) {
                awareness = proximity = gain = phase = time = hiss = 0;
                stingTime = -1;
                seenGeneration = mix.generation();
                seenSting = mix.sting();
                seenDoor = doorSerial;
                doorTime = -1;
            }
            if (seenDoor != doorSerial) {
                seenDoor = doorSerial;
                if (mix.active() && mix.volume() > 0) { doorTime = 0; doorClosed = closed; }
            }
            if (!mix.active() || mix.volume() == 0) doorTime = -1;
            if (mix.sting() != seenSting) {
                seenSting = mix.sting();
                if (mix.active() && mix.volume() > 0) stingTime = 0;
            }
            if (!mix.active() || mix.volume() == 0) stingTime = -1;
            double targetGain = mix.active() ? mix.volume() : 0;
            for (int i = 0; i < pcm.length; i += 2) {
                awareness += (mix.awareness() - awareness) / (CUE_SAMPLE_RATE * 0.06);
                proximity += (mix.proximity() - proximity) / (CUE_SAMPLE_RATE * 0.25);
                gain += (targetGain - gain) / (CUE_SAMPLE_RATE * 0.015);
                time += 1.0 / CUE_SAMPLE_RATE;
                phase = (phase + (60 + 80 * awareness) / (60 * CUE_SAMPLE_RATE)) % 1;
                hiss = 0.87 * hiss + 0.13 * (random.nextDouble() * 2 - 1);
                double drone = Math.sin(2 * Math.PI * 54 * time)
                        + 0.35 * Math.sin(2 * Math.PI * 81 * time)
                        + 0.20 * Math.sin(2 * Math.PI * 108 * time);
                double nearby = 0.028 * proximity * (1 - awareness * 0.75)
                        * (Math.sin(2 * Math.PI * 66 * time)
                        * (0.7 + 0.3 * Math.sin(2 * Math.PI * 0.9 * time)) + 1.2 * hiss);
                double heartbeat = 0.22 * Math.max(0, (awareness - 0.25) / 0.75)
                        * WarningCues.beat(phase) * Math.sin(2 * Math.PI * 68 * time);
                double sample = (0.006 + 0.055 * awareness) * drone + nearby + heartbeat
                        + (0.012 * awareness + 0.025 * awareness * awareness) * hiss;
                if (stingTime >= 0) {
                    sample += 0.12 * Math.min(1, stingTime / 0.015)
                            * Math.exp(-6 * stingTime)
                            * (Math.sin(2 * Math.PI * (220 * stingTime
                            - 70 * stingTime * stingTime)) + 0.3 * hiss);
                    stingTime += 1.0 / CUE_SAMPLE_RATE;
                    if (stingTime >= 0.8) stingTime = -1;
                }
                if (doorTime >= 0) {
                    double duration = doorClosed ? 0.35 : 0.12;
                    sample += (doorClosed ? 0.20 : 0.07) * Math.min(1, doorTime / 0.008)
                            * Math.exp(-doorTime * (doorClosed ? 13 : 35))
                            * (2.0 * hiss + Math.sin(2 * Math.PI * 110 * doorTime));
                    doorTime += 1.0 / CUE_SAMPLE_RATE;
                    if (doorTime >= duration) doorTime = -1;
                }
                // Smooth gain changes avoid clicks; muted output becomes exact silence.
                double output = gain < 0.00001 ? 0 : sample * gain;
                short value = (short) Math.round(Math.max(-0.9, Math.min(0.9, output)) * 32767);
                pcm[i] = (byte) value;
                pcm[i + 1] = (byte) (value >> 8);
            }
            pulse = WarningCues.beat(phase);
        }
    }

    static final class WarningAudio implements AutoCloseable {
        static final AudioFormat FORMAT = new AudioFormat(CUE_SAMPLE_RATE, 16, 1, true, false);
        volatile CueMix target = new CueMix(0, 0, 0, false, 0, 0);
        private volatile AudioSession session;
        private volatile long doorSerial;
        private volatile boolean doorClosed;

        void doorSound(boolean closed) {
            doorClosed = closed;
            doorSerial++;
        }

        synchronized void start() {
            if (session != null || GraphicsEnvironment.isHeadless()) return;
            AudioSession next = new AudioSession();
            session = next;
            Thread worker = new Thread(next, "DeadSignal-warning-audio");
            worker.setDaemon(true);
            worker.start();
        }

        boolean available() {
            AudioSession current = session;
            return current != null && current.available;
        }

        double pulse() {
            AudioSession current = session;
            return current == null ? 0 : current.pulse;
        }

        @Override
        public synchronized void close() {
            AudioSession current = session;
            session = null;
            if (current != null) current.running = false;
        }

        private final class AudioSession implements Runnable {
            volatile boolean running = true;
            volatile boolean available;
            volatile double pulse;

            @Override
            public void run() {
                SourceDataLine line = null;
                try {
                    line = AudioSystem.getSourceDataLine(FORMAT);
                    line.open(FORMAT, 4096);
                    if (!running) return;
                    line.start();
                    available = true;
                    CueSynth synth = new CueSynth();
                    byte[] block = new byte[1024];
                    while (running) {
                        synth.render(block, target, doorSerial, doorClosed);
                        pulse = synth.pulse;
                        int offset = 0;
                        while (running && offset < block.length) {
                            int written = line.write(block, offset, block.length - offset);
                            if (written <= 0) break;
                            offset += written;
                        }
                    }
                } catch (LineUnavailableException | IllegalArgumentException | SecurityException ex) {
                    System.err.println("Warning audio unavailable; visual cues remain active: "
                            + ex.getMessage());
                } finally {
                    available = false;
                    if (line != null) {
                        if (line.isOpen()) {
                            line.stop();
                            line.flush();
                        }
                        line.close();
                    }
                }
            }
        }
    }


    // -------------------------------------------------
    // MAIN MENU
    // -------------------------------------------------

    static class MainMenuPanel extends JPanel {
        private static final int DESIGN_WIDTH = 840;
        private static final int DESIGN_HEIGHT = 688;
        private static final int TEXT_WIDTH = 600;
        private static final int BUTTON_WIDTH = 250;
        private static final int BUTTON_HEIGHT = 45;
        private static final int BUTTON_GAP = 10;
        private static final double MIN_UI_SCALE = 0.80;
        private static final double MAX_UI_SCALE = 1.60;
        private static final int[] MOUNTAIN_X =
                {0, 90, 180, 270, 360, 450, 540, 630, 720, 840};
        private static final int[] MOUNTAIN_Y =
                {390, 270, 350, 240, 370, 280, 360, 250, 340, 270};

        private final Color red = new Color(170, 25, 38);
        private final JFrame frame;
        private final GameSession session;
        private final JPanel menuColumn = new JPanel();
        private final GridLayout buttonLayout = new GridLayout(5, 1);
        private final JPanel buttons = new JPanel(buttonLayout);
        private final JLabel title;
        private final JLabel subtitle;
        private final JLabel status;
        private final JLabel developer;
        private final int[] fogX = new int[35];
        private final int[] fogY = new int[35];
        private double appliedScale = -1;
        private int appliedTextWidth = -1;

        MainMenuPanel(JFrame frame) {
            this(frame, new GameSession(frame));
        }

        MainMenuPanel(JFrame frame, GameSession session) {
            this.frame = frame;
            this.session = session;
            setPreferredSize(new Dimension(DESIGN_WIDTH, DESIGN_HEIGHT));
            setLayout(new BorderLayout());
            setBackground(Color.BLACK);

            menuColumn.setOpaque(false);
            menuColumn.setLayout(new BoxLayout(menuColumn, BoxLayout.Y_AXIS));
            buttons.setOpaque(false);
            buttons.setAlignmentX(Component.CENTER_ALIGNMENT);

            title = createLabel("DEAD SIGNAL", new Color(190, 35, 45));
            subtitle = createLabel("THE LAST NIGHT", new Color(160, 160, 160));
            status = createLabel("YOUR NIGHTMARE IS WAITING...", new Color(115, 115, 125));
            menuColumn.add(title);
            menuColumn.add(subtitle);
            menuColumn.add(status);
            menuColumn.add(buttons);

            // Center the whole column using the current window's content area.
            JPanel center = new JPanel(new GridBagLayout());
            center.setOpaque(false);
            center.add(menuColumn);
            add(center, BorderLayout.CENTER);

            developer = createLabel("A GROUP 2 PRODUCTION | VERSION 1.0",
                    new Color(95, 95, 105));
            add(developer, BorderLayout.SOUTH);

            JButton newGame = createButton("NEW GAME");
            buttons.add(newGame);
            newGame.addActionListener(e -> startGame());

            JButton continueGame = createButton("CONTINUE");
            buttons.add(continueGame);
            continueGame.setEnabled(session.canContinue());
            continueGame.addActionListener(e -> session.continueGame());

            JButton settings = createButton("SETTINGS");
            buttons.add(settings);
            settings.addActionListener(e -> showWarningSettings(frame));

            JButton credits = createButton("CREDITS");
            buttons.add(credits);
            credits.addActionListener(e -> JOptionPane.showMessageDialog(
                    frame, "DEAD SIGNAL\n\nDeveloped by Group 2\n\nThank you for playing.",
                    "CREDITS", JOptionPane.INFORMATION_MESSAGE));

            JButton exit = createButton("EXIT GAME");
            buttons.add(exit);
            exit.addActionListener(e -> session.requestExit());

            // Keep the existing atmosphere stable when buttons repaint on hover.
            Random random = new Random();
            for (int i = 0; i < fogX.length; i++) {
                fogX[i] = random.nextInt(DESIGN_WIDTH);
                fogY[i] = 420 + random.nextInt(250);
            }
            updateResponsiveSizes(DESIGN_WIDTH, DESIGN_HEIGHT);
        }

        private JLabel createLabel(String text, Color color) {
            JLabel label = new JLabel(text, SwingConstants.CENTER);
            label.setForeground(color);
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
            return label;
        }

        private static int scaled(int value, double scale) {
            return Math.max(1, (int) Math.round(value * scale));
        }

        private void sizeLabel(JLabel label, int fontSize, int fontStyle,
                               int width, int topGap, int bottomGap, double scale) {
            label.setFont(new Font("Monospaced", fontStyle, scaled(fontSize, scale)));
            int top = (int) Math.round(topGap * scale);
            int bottom = (int) Math.round(bottomGap * scale);
            label.setBorder(BorderFactory.createEmptyBorder(top, 0, bottom, 0));
            int height = label.getFontMetrics(label.getFont()).getHeight() + top + bottom;
            Dimension size = new Dimension(width, height);
            label.setPreferredSize(size);
            label.setMinimumSize(size);
            label.setMaximumSize(size);
        }

        private void updateResponsiveSizes(int width, int height) {
            double scale = Math.max(MIN_UI_SCALE, Math.min(MAX_UI_SCALE,
                    Math.min((double) width / DESIGN_WIDTH,
                            (double) height / DESIGN_HEIGHT)));
            int padding = scaled(24, scale);
            int textWidth = Math.max(1,
                    Math.min(scaled(TEXT_WIDTH, scale), width - padding * 2));
            if (scale == appliedScale && textWidth == appliedTextWidth) {
                return;
            }
            appliedScale = scale;
            appliedTextWidth = textWidth;

            setBorder(BorderFactory.createEmptyBorder(
                    scaled(16, scale), padding, scaled(20, scale), padding));
            sizeLabel(title, 43, Font.BOLD, textWidth, 0, 0, scale);
            sizeLabel(subtitle, 17, Font.PLAIN, textWidth, 8, 0, scale);
            sizeLabel(status, 11, Font.PLAIN, textWidth, 18, 44, scale);

            int buttonWidth = Math.min(textWidth, scaled(BUTTON_WIDTH, scale));
            int buttonHeight = scaled(BUTTON_HEIGHT, scale);
            int gap = scaled(BUTTON_GAP, scale);
            buttonLayout.setVgap(gap);
            for (Component button : buttons.getComponents()) {
                button.setFont(new Font("Monospaced", Font.BOLD, scaled(15, scale)));
            }
            Dimension buttonSize = new Dimension(buttonWidth,
                    buttons.getComponentCount() * buttonHeight
                            + (buttons.getComponentCount() - 1) * gap);
            buttons.setPreferredSize(buttonSize);
            buttons.setMinimumSize(buttonSize);
            buttons.setMaximumSize(buttonSize);

            developer.setFont(new Font("Monospaced", Font.PLAIN, scaled(10, scale)));
            developer.setBorder(BorderFactory.createEmptyBorder(scaled(24, scale), 0, 0, 0));

            // Refresh cached child sizes before the centering layout runs.
            buttons.invalidate();
            menuColumn.invalidate();
        }

        @Override
        public void doLayout() {
            updateResponsiveSizes(getWidth(), getHeight());
            super.doLayout();
        }

        private void startGame() {
            if (frame != null && frame.getContentPane() != this) {
                return;
            }
            session.startNewGame(buttons.getComponent(0).getFont());
        }

        private JButton createButton(String text) {
            JButton button = new JButton(text);
            button.setBackground(new Color(25, 18, 23));
            button.setForeground(Color.WHITE);
            button.setFont(new Font("Monospaced", Font.BOLD, 15));
            button.setFocusPainted(false);
            button.setBorder(BorderFactory.createLineBorder(red, 2));
            button.setCursor(new Cursor(Cursor.HAND_CURSOR));
            button.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (!button.isEnabled()) return;
                    button.setBackground(new Color(115, 18, 30));
                    button.setBorder(BorderFactory.createLineBorder(new Color(220, 45, 55), 2));
                }
                @Override public void mouseExited(MouseEvent e) {
                    button.setBackground(new Color(25, 18, 23));
                    button.setBorder(BorderFactory.createLineBorder(red, 2));
                }
            });
            return button;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new GradientPaint(0, 0, new Color(5, 7, 13),
                    0, height, new Color(24, 13, 18)));
            g2.fillRect(0, 0, width, height);

            double scaleX = (double) width / DESIGN_WIDTH;
            double scaleY = (double) height / DESIGN_HEIGHT;
            // Scale the moon uniformly, even on a very wide display.
            int moonSize = scaled(90, Math.min(scaleX, scaleY));
            g2.setColor(new Color(180, 180, 190, 35));
            g2.fillOval((int) Math.round(640 * scaleX),
                    (int) Math.round(65 * scaleY), moonSize, moonSize);

            g2.setColor(new Color(15, 17, 23));
            int[] x = new int[MOUNTAIN_X.length];
            int[] y = new int[MOUNTAIN_Y.length];
            for (int i = 0; i < x.length; i++) {
                x[i] = (int) Math.round(MOUNTAIN_X[i] * scaleX);
                y[i] = (int) Math.round(MOUNTAIN_Y[i] * scaleY);
            }
            g2.fillPolygon(x, y, x.length);

            int groundY = (int) Math.round(430 * scaleY);
            g2.setColor(new Color(12, 12, 17));
            g2.fillRect(0, groundY, width, height - groundY);
            g2.setColor(new Color(80, 20, 27, 80));
            for (int i = 0; i < fogX.length; i++) {
                g2.fillOval((int) Math.round(fogX[i] * scaleX),
                        (int) Math.round(fogY[i] * scaleY),
                        scaled(80, scaleX), scaled(3, scaleY));
            }
            g2.setColor(new Color(120, 20, 30, 35));
            int edgeHeight = scaled(3, scaleY);
            g2.fillRect(0, 0, width, edgeHeight);
            g2.fillRect(0, height - edgeHeight, width, edgeHeight);
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("DEAD SIGNAL: THE SHIFTING FACILITY");
            GameSession session = new GameSession(frame);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { session.requestExit(); }
            });
            session.showMenu();

            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setMinimumSize(new Dimension(1000, 700));

            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
