package com.mycompany.deadsignal;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Asset loading, facing, animation, and room-art visibility checks. */
public final class ArtAssetsCheck {
    static int checks;
    static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static final class Clock {
        long millis = 100_000;
        final Main game = new Main(() -> millis * 1_000_000L, () -> millis);
    }
    static void assets() throws Exception {
        int width = -1, height = -1;
        for (BufferedImage[] group : new BufferedImage[][] {
                Main.SpriteAssets.MONSTER_WALK_RIGHT, Main.SpriteAssets.MONSTER_WALK_LEFT}) {
            require(group.length == 3, "Walking animation must have three frames");
            for (BufferedImage frame : group) {
                require(frame != null && frame.getColorModel().hasAlpha(), "Missing transparent monster frame");
                if (width < 0) { width = frame.getWidth(); height = frame.getHeight(); }
                require(frame.getWidth() == width && frame.getHeight() == height,
                        "Frames have inconsistent crops or foot baselines");
            }
        }
        for (String side : new String[] {"left", "right"}) for (int n = 1; n <= 3; n++) {
            String path = String.format("/assets/monster/monster_walk_%s_%02d.png", side, n);
            try (var stream = Main.class.getResourceAsStream(path)) {
                require(stream != null, "Asset was not bundled: " + path);
                BufferedImage image = ImageIO.read(stream);
                require(image.getWidth() == 64 && image.getHeight() == 64, "Original canvas changed");
                require((image.getRGB(0, 0) >>> 24) == 0 && (image.getRGB(63, 63) >>> 24) == 0,
                        "Sprite still has an opaque background");
                int clear = 0, opaque = 0;
                for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    if (alpha == 0) clear++; else opaque++;
                }
                require(clear > 3000 && opaque > 800, "Background extraction lost the subject or its gaps");
            }
        }
        require(Main.SpriteAssets.ROOM_ONE != null
                && Main.SpriteAssets.ROOM_ONE.getWidth() == Main.ROOM_W * Main.TILE
                && Main.SpriteAssets.ROOM_ONE.getHeight() == Main.ROOM_H * Main.TILE,
                "Room 1 artwork does not match the room footprint");
    }
    static void facingAndAnimation() {
        Clock clock = new Clock(); Main game = clock.game;
        int[] start = game.roomTile(4, 3, 2);
        game.monsterX = start[0]; game.monsterY = start[1];
        require(game.currentMonsterSprite() == Main.SpriteAssets.MONSTER_WALK_RIGHT[0], "Idle starting pose changed");
        require(game.stepMonster(new Point(start[0] - 1, start[1])) && game.monsterFacingLeft,
                "Left step does not face left");
        require(game.stepMonster(new Point(game.monsterX, game.monsterY - 1)) && game.monsterFacingLeft,
                "Vertical movement loses last side facing");
        Set<BufferedImage> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int n = 0; n < 17; n++) { frames.add(game.currentMonsterSprite()); clock.millis += 20; }
        require(frames.size() == 3, "Successful walking does not animate all frames");
        clock.millis += 100;
        require(game.currentMonsterSprite() == Main.SpriteAssets.MONSTER_WALK_LEFT[0], "Idle animation keeps walking");
        require(game.stepMonster(new Point(game.monsterX + 1, game.monsterY)) && !game.monsterFacingLeft,
                "Right step does not face right");
        game.monsterStunnedUntil = game.gameNow() + 2000;
        require(game.currentMonsterSprite() == Main.SpriteAssets.MONSTER_WALK_RIGHT[0], "Stunned monster keeps walking");
        long step = game.lastMonsterStepAt;
        require(!game.stepMonster(new Point(game.monsterX - 1, game.monsterY))
                && !game.monsterFacingLeft && game.lastMonsterStepAt == step,
                "Blocked movement changes facing or animation time");
        clock.millis += 2000;
        Main.Door door = null;
        for (Main.Door[] group : game.roomDoors) for (Main.Door candidate : group) {
            if (candidate.active) door = candidate;
        }
        door.open = false;
        require(!game.stepMonster(new Point(door.x, door.y)) && game.lastMonsterStepAt == step,
                "Door windup looks like successful walking");
        game.restartGame();
        require(!game.monsterFacingLeft && game.currentMonsterSprite() == Main.SpriteAssets.MONSTER_WALK_RIGHT[0],
                "Restart retains old facing or motion");
    }
    static BufferedImage background(Main game) {
        BufferedImage image = new BufferedImage(Main.COLS * Main.TILE, Main.ROWS * Main.TILE,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); game.drawRoomOneBackground(g); g.dispose(); return image;
    }
    static void matchesRoom(BufferedImage drawn, int[] origin) {
        BufferedImage art = Main.SpriteAssets.ROOM_ONE;
        for (int y = 0; y < art.getHeight(); y++) for (int x = 0; x < art.getWidth(); x++) {
            require(drawn.getRGB(origin[0] * Main.TILE + x, origin[1] * Main.TILE + y) == art.getRGB(x, y),
                    "Room artwork changed, was distorted, or moved to a different room");
        }
    }
    static void roomArtwork() {
        Clock clock = new Clock(); Main game = clock.game;
        game.updateCeilings();
        int[] original = game.roomTile(0, 0, 0);
        matchesRoom(background(game), original);
        int[] other = game.roomTile(1, 1, 1);
        game.playerX = other[0]; game.playerY = other[1]; game.updateCeilings();
        BufferedImage hidden = background(game);
        require(Arrays.stream(hidden.getRGB(0, 0, hidden.getWidth(), hidden.getHeight(), null, 0,
                hidden.getWidth())).allMatch(pixel -> (pixel >>> 24) == 0), "Background reveals a covered room");
        require(game.shiftRooms(), "Cannot shift rooms for art test");
        int[] moved = game.roomTile(0, 0, 0);
        require(!Arrays.equals(original, moved), "Room 1 did not exchange locations");
        game.playerX = moved[0] + 1; game.playerY = moved[1] + 1; game.updateCeilings();
        BufferedImage shifted = background(game); matchesRoom(shifted, moved);
        require((shifted.getRGB(original[0] * Main.TILE, original[1] * Main.TILE) >>> 24) == 0,
                "Background left behind at old room slot");
        for (int y = moved[1]; y < moved[1] + Main.ROOM_H; y++) for (int x = moved[0]; x < moved[0] + Main.ROOM_W; x++) {
            require(!game.terrainWall(x, y), "Decorative furniture added collision");
        }
    }
    static void previews(File output) throws Exception {
        Clock clock = new Clock(); Main game = clock.game;
        int[] player = game.roomTile(0, 1, 2), monster = game.roomTile(0, 3, 2);
        game.playerX = player[0]; game.playerY = player[1]; game.faceX = 1; game.faceY = 0;
        game.monsterX = monster[0]; game.monsterY = monster[1];
        game.setSize(1260, 1032);
        for (boolean left : new boolean[] {false, true}) {
            game.monsterFacingLeft = left;
            BufferedImage image = new BufferedImage(1260, 1032, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); game.paint(g); g.dispose();
            ImageIO.write(image, "png", new File(output, left ? "monster-left-room-one.png" : "monster-right-room-one.png"));
        }
    }
    public static void main(String[] args) throws Exception {
        File output = new File(args.length == 0 ? "build/art-checks" : args[0]); output.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            try { assets(); facingAndAnimation(); roomArtwork(); previews(output); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        });
        System.out.println("Art asset checks passed: " + checks);
    }
}
