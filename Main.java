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

final class Main extends JPanel {

    // -------------------------------------------------
    // CHARACTER SPRITES
    // -------------------------------------------------

    // PNG files live in src/main/resources/assets/player and .../monster.
    // They are loaded from the classpath, so no computer-specific paths.
    static final class SpriteAssets {

        // Side-view sprites face LEFT. Set to false if yours face right.
        static final boolean SIDE_SPRITES_FACE_LEFT = true;

        static final BufferedImage[] PLAYER_FRONT = loadFrames(
                "player/tambay1front.png",
                "player/tambay2.png",
                "player/Tambay-3.png"
        );
        static final BufferedImage[] PLAYER_SIDE = loadFrames(
                "player/tambay4.png",
                "player/tambay5.png",
                "player/tambay6.png",
                "player/tambay7.png"
        );
        static final BufferedImage[] PLAYER_BACK = loadFrames(
                "player/tambay8.png",
                "player/tambay9.png",
                "player/tambay10.png",
                "player/tambay11.png",
                "player/tambay12.png"
        );
        // Only a front view exists, so every direction uses these frames
        static final BufferedImage[] MONSTER = loadFrames(
                "monster/tikbalang1.png",
                "monster/Tikabalang2.png",
                "monster/tikbalang3.png",
                "monster/tikabalang4.png",
                "monster/tikbalang5.png",
                "monster/tikbalang6.png"
        );

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
    // Press M in DEBUG mode to remove every ceiling (for testing room shifting).
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
    static final double ITEM_SHUFFLE_CHANCE = 0.60;  // items in empty rooms move

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
        final int a;               // room id on the left / top
        final int b;               // room id on the right / bottom
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
    // Set once per game and never changes: a hallway's whole gap (the
    // corridor AND the solid tiles beside it) is one section.
    int[][] section = new int[ROWS][COLS];

    // Ceiling state per section: true = roof is on, interior is hidden
    boolean[] ceilingOn = new boolean[ROOM_COUNT + CONN_COUNT];
    boolean revealAll = false; // DEBUG only (press M): removes all ceilings

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
            clearMovementInput();
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
        this.movementClock = movementClock;
        setPreferredSize(new Dimension(
                COLS * TILE, ROWS * TILE + PANEL_H
        ));

        setBackground(Color.BLACK);
        setFocusable(true);

        setupControls();

        restartGame(); // builds the first game

        // Timers start when the game panel is attached to the window.
    }

    @Override
    public void addNotify() {
        super.addNotify();
        clearMovementInput();
        movementWindow = SwingUtilities.getWindowAncestor(this);
        if (movementWindow != null) {
            movementWindow.addWindowFocusListener(movementFocusListener);
        }
        movementTimer.start();
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
        super.removeNotify();
    }

    // -------------------------------------------------
    // MAP GENERATION
    // -------------------------------------------------

    // Returns the {x, y} tile at (dx, dy) inside a room
    int[] roomTile(int room, int dx, int dy) {
        int col = room % ROOM_COLS;
        int row = room / ROOM_COLS;
        return new int[] {roomX0[col] + dx, roomY0[row] + dy};
    }

    // Picks hallway lengths, places rooms and builds the first layout
    void buildMap() {

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
    }

    // Rebuilds the whole map from the room grid + the open connections.
    // Rooms never change, so rebuilding never alters a room's own tiles.
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

    // Marks which section every tile belongs to. This never changes, so a
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
        if (x < 0 || y < 0 ||
                x >= COLS || y >= ROWS) {
            return true;
        }

        return map[y][x] == '#';
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

        if (!c.open || map[y][x] != 'h' || (c.a != cur && c.b != cur)) {
            return false;
        }

        int colA = c.a % ROOM_COLS;
        int rowA = c.a / ROOM_COLS;

        // Distance in tiles from the player's room to this hallway tile
        int dist;

        if (c.horizontal) {
            dist = (c.a == cur)
                    ? x - (roomX0[colA] + ROOM_W)
                    : (roomX0[colA + 1] - 1) - x;
        } else {
            dist = (c.a == cur)
                    ? y - (roomY0[rowA] + ROOM_H)
                    : (roomY0[rowA + 1] - 1) - y;
        }

        return dist < HALL_THRESHOLD;
    }

    // True if a roof is hiding this tile
    boolean isCovered(int x, int y) {

        int s = sectionAt(x, y);

        return s >= 0 && ceilingOn[s] &&
                !(DEBUG && revealAll) &&
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

        bindAction("E", "grab", this::grabItem);
        bindAction("Q", "throw", this::throwItem);
        bindAction("F", "locker", this::toggleLocker);
        bindAction("R", "restart", this::restartGame);
        bindAction("M", "reveal", () -> {
            if (DEBUG) {
                revealAll = !revealAll;
                repaint();
            }
        });
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
                if (gameOver || won || hidden) {
                    clearMovementInput();
                    return;
                }
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
        updatePlayerMovement(movementClock.getAsLong());
    }

    void updatePlayerMovement(long nowNanos) {
        if (gameOver || won || hidden) {
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
        movePlayer(dx, dy, running, movementClock.getAsLong());
    }

    void movePlayer(int dx, int dy, boolean running, long nowNanos) {

        if (gameOver || won) {
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
        lastPlayerMove = System.currentTimeMillis();

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

        if (gameOver || won) {
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

        if (gameOver || won) {
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

        if (gameOver || won) {
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

        long now = System.currentTimeMillis();

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

        long now = System.currentTimeMillis();

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

        long now = System.currentTimeMillis();

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
        if (a >= 0.70) {
            double pulse = 0.78 + 0.22 * Math.sin(now / 110.0);
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

        long now = System.currentTimeMillis();

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

        long now = System.currentTimeMillis();

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
            monsterX = next.x;
            monsterY = next.y;
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

        monsterX = next.x;
        monsterY = next.y;

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

        monsterX = next.x;
        monsterY = next.y;
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

        monsterX = next.x;
        monsterY = next.y;
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

                if (visited[ny][nx] || isWall(nx, ny)) {
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
        int hall = HALL_BASE + i;

        return playerSec == hall || monsterSec == hall ||
                playerSec == c.a || playerSec == c.b ||
                monsterSec == c.a || monsterSec == c.b;
    }

    // Timer expired: figure out what is occupied, protect it,
    // and shift everything else.
    void shiftRooms() {

        int playerSec = sectionAt(playerX, playerY);
        int monsterSec = sectionAt(monsterX, monsterY);

        boolean[] frozen = new boolean[conns.length];

        for (int i = 0; i < conns.length; i++) {
            frozen[i] = isFrozen(i, playerSec, monsterSec);
        }

        int changed = reshuffleConnections(frozen);

        applyLayout();

        int moved = shuffleRoomContents(playerSec, monsterSec);
        int rescued = relocateStrandedItems(playerSec);

        if (DEBUG) {
            System.out.println("Shift #" + shiftNumber
                    + ": protected " + sectionName(playerSec)
                    + " (player) and " + sectionName(monsterSec)
                    + " (monster); " + changed + " hallways changed, "
                    + moved + " items moved, "
                    + rescued + " items rescued from walls");

            if (isWall(playerX, playerY) || isWall(monsterX, monsterY)) {
                System.out.println("WARNING: a character is inside a wall!");
            }
        }
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

    // Items lying in empty rooms get moved around inside that room
    int shuffleRoomContents(int playerSec, int monsterSec) {

        int moved = 0;

        for (Item it : items) {

            int s = sectionAt(it.x, it.y);

            if (s < 0 || s >= ROOM_COUNT ||
                    s == playerSec || s == monsterSec) {
                continue;
            }

            if (random.nextDouble() < ITEM_SHUFFLE_CHANCE) {
                Point p = freeTileInRoom(s);
                it.x = p.x;
                it.y = p.y;
                moved++;
            }
        }

        return moved;
    }

    // An item lying in a hallway that just got sealed would be inside a
    // wall, so move it to a random room the player is not in.
    int relocateStrandedItems(int playerSec) {

        int rescued = 0;

        for (Item it : items) {

            if (!isWall(it.x, it.y)) {
                continue;
            }

            int room;
            int tries = 0;

            do {
                room = random.nextInt(ROOM_COUNT);
                tries++;
            } while (room == playerSec && tries < 20);

            Point p = freeTileInRoom(room);
            it.x = p.x;
            it.y = p.y;
            rescued++;
        }

        return rescued;
    }

    void updateGame() {

        if (gameOver || won) {
            repaint();
            return;
        }

        long now = System.currentTimeMillis();

        updateNoise(now);

        if (now >= nextShiftAt) {

            shiftNumber++;

            shiftRooms();

            nextShiftAt = now + nextShiftDelay();

            // The player only hears it, never sees it
            message = "Somewhere, metal groans and the walls shift...";
        }

        updateMonster();

        updateAwareness(now); // after the AI has decided its new state

        repaint();
    }

    void restartGame() {

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

        pickPatrolPoint();
        spawnItems();

        long now = System.currentTimeMillis();

        nextShiftAt = now + nextShiftDelay();
        lastNoiseUpdate = now;
        lastAwareUpdate = now;
        lastMonsterMove = now;
        lastPlayerMove = 0;
        lastPlayerStepNanos = 0;
        playerHasStepped = false;

        message = "Find the keycard and escape!";

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

        // Draw monster (only if the player can actually see it)
        // Normal play: only when its tile has no ceiling and it is in view.
        // Debug: also shown when [M] reveal removed every ceiling.
        if ((DEBUG && revealAll) ||
                (canSeeTile(monsterX, monsterY) && playerSeesMonster())) {

            // The monster is a tall creature: feet on its tile, head
            // reaching into the tile above. It sways slowly even when
            // standing still, so it never looks frozen.
            long spriteTime = System.currentTimeMillis();

            BufferedImage monsterSprite = SpriteAssets.frame(
                    SpriteAssets.MONSTER, spriteTime, spriteTime, 250);

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

        // Draw the player: sprite set depends on the facing direction
        long playerTime = System.currentTimeMillis();
        BufferedImage[] playerFrames;
        boolean flipPlayer = false;

        if (faceY > 0) {
            playerFrames = SpriteAssets.PLAYER_FRONT;
        } else if (faceY < 0) {
            playerFrames = SpriteAssets.PLAYER_BACK;
        } else {
            playerFrames = SpriteAssets.PLAYER_SIDE;
            flipPlayer = SpriteAssets.SIDE_SPRITES_FACE_LEFT
                    ? faceX > 0 : faceX < 0;
        }

        BufferedImage playerSprite = SpriteAssets.frame(
                playerFrames, playerTime, lastPlayerMove, 130);

        SpriteAssets.draw(g2, playerSprite,
                playerX * TILE + TILE / 2,
                playerY * TILE + TILE,
                TILE - 3, TILE,
                hidden ? 0.38f : 1.0f, flipPlayer);

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
                "E: Grab | Q: Throw | F: Hide | R: Restart",
                250, panelY + 54
        );

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
                    0, nextShiftAt - System.currentTimeMillis()
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
                    "Protected: " + sectionName(protectedSec)
                            + " + " + sectionName(monsterSec),
                    560, panelY + 28
            );

            g2.drawString(
                    String.format("Shift timer: %02d:%02d",
                            secs / 60, secs % 60),
                    560, panelY + 42
            );

            g2.drawString(
                    revealAll ? "Ceilings off: ALL (reveal)"
                            : "Ceiling off: " + sectionName(cur),
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
                    "[M] reveal map: " + (revealAll ? "ON" : "off"),
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
            this.frame = frame;
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
            continueGame.addActionListener(e -> JOptionPane.showMessageDialog(
                    frame, "No saved game found.", "CONTINUE", JOptionPane.WARNING_MESSAGE));

            JButton settings = createButton("SETTINGS");
            buttons.add(settings);
            settings.addActionListener(e -> JOptionPane.showMessageDialog(
                    frame, "GAME SETTINGS\n\nSound: ON\nDifficulty: NORMAL\nDisplay: WINDOWED",
                    "SETTINGS", JOptionPane.PLAIN_MESSAGE));

            JButton credits = createButton("CREDITS");
            buttons.add(credits);
            credits.addActionListener(e -> JOptionPane.showMessageDialog(
                    frame, "DEAD SIGNAL\n\nDeveloped by Group 2\n\nThank you for playing.",
                    "CREDITS", JOptionPane.INFORMATION_MESSAGE));

            JButton exit = createButton("EXIT GAME");
            buttons.add(exit);
            exit.addActionListener(e -> {
                int answer = JOptionPane.showConfirmDialog(frame,
                        "Are you sure you want to leave the nightmare?", "EXIT GAME",
                        JOptionPane.YES_NO_OPTION);
                if (answer == JOptionPane.YES_OPTION) System.exit(0);
            });

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
            Main game = new Main();
            frame.setContentPane(game);
            frame.revalidate();
            frame.repaint();
            game.requestFocusInWindow();
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
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            frame.setContentPane(new MainMenuPanel(frame));

            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setMinimumSize(new Dimension(1000, 700));

            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}