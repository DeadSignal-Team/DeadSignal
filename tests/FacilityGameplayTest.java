package com.mycompany.deadsignal;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class FacilityGameplayTest {
    static int checks;
    static final class Clock {
        long millis = 100_000;
        final Main game = new Main(() -> millis * 1_000_000L, () -> millis);
        void advance(long value) { millis += value; }
    }
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static void key(Main game, int code, boolean released) {
        key(game, code, released, 0);
    }
    static void key(Main game, int code, boolean released, int modifiers) {
        Object name = game.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(code, modifiers, released));
        check(name != null, "Missing key binding " + code + " released=" + released);
        game.getActionMap().get(name).actionPerformed(new ActionEvent(game, 0, String.valueOf(name)));
    }
    static Set<Main.Item> identities(Main game) {
        Set<Main.Item> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(game.items);
        if (game.held != null) set.add(game.held);
        return set;
    }
    static void roomConservation() {
        Clock clock = new Clock(); Main game = clock.game;
        game.held = game.items.remove(0);
        Main.Item carried = game.held;
        Set<Main.Item> original = identities(game);
        Main.Door persistent = game.roomDoors[4][1]; persistent.open = false;
        int playerRoom = game.sectionAt(game.playerX, game.playerY);
        int playerSlot = game.slotOfRoom[playerRoom];
        int px = game.playerX, py = game.playerY;
        for (int shift = 0; shift < 1000; shift++) {
            IdentityHashMap<Main.Item, Main.RoomAnchor> before = new IdentityHashMap<>();
            for (Main.Item item : game.items) before.put(item, game.anchorAt(item.x, item.y));
            Main.RoomAnchor monster = game.anchorAt(game.monsterX, game.monsterY);
            Main.RoomAnchor key = game.anchorAt(game.keyX, game.keyY), exit = game.anchorAt(game.exitX, game.exitY);
            Main.RoomAnchor[] lockers = new Main.RoomAnchor[game.lockers.length];
            for (int i = 0; i < lockers.length; i++) lockers[i] = game.anchorAt(game.lockers[i][0], game.lockers[i][1]);
            int[] previousSlots = game.slotOfRoom.clone();
            check(game.shiftRooms(), "Room shift unexpectedly deferred in a room");
            check(game.roomsConnected(), "Facility disconnected");
            check(game.slotOfRoom[playerRoom] == playerSlot && game.playerX == px && game.playerY == py,
                    "Player's room or position moved");
            check(identities(game).equals(original) && game.held == carried, "Items added, removed, replaced, or dropped");
            boolean[] slots = new boolean[Main.ROOM_COUNT];
            for (int room = 0; room < Main.ROOM_COUNT; room++) {
                int slot = game.slotOfRoom[room];
                check(!slots[slot] && game.roomAtSlot[slot] == room, "Room mapping is not bijective");
                slots[slot] = true;
                if (room != playerRoom) check(slot != previousSlots[room], "Eligible room did not exchange locations");
            }
            for (Main.Item item : game.items) {
                check(before.get(item).equals(game.anchorAt(item.x, item.y)), "Item changed local room position");
                check(!game.isWall(item.x, item.y), "Item stranded in a wall or closed door");
            }
            check(monster.equals(game.anchorAt(game.monsterX, game.monsterY)), "Monster did not travel with its room");
            check(key.equals(game.anchorAt(game.keyX, game.keyY)) && exit.equals(game.anchorAt(game.exitX, game.exitY)),
                    "Objective changed room or local position");
            for (int i = 0; i < lockers.length; i++) {
                check(lockers[i].equals(game.anchorAt(game.lockers[i][0], game.lockers[i][1])), "Locker detached from room");
            }
            check(game.roomDoors[4][1] == persistent && !persistent.open, "Door identity/state lost while moving rooms");
            check(!game.isWall(game.playerX, game.playerY) && !game.isWall(game.monsterX, game.monsterY),
                    "Character stranded in wall");
        }
        System.out.println("PASS 1000 room exchanges: object conservation, fixed player room, contents, objectives, doors, connectivity");
    }
    static void hallwayDeferralAndItems() {
        Clock clock = new Clock(); Main game = clock.game;
        Main.Door entry = firstDoor(game);
        game.playerX = entry.x; game.playerY = entry.y;
        int[] slots = game.slotOfRoom.clone();
        game.nextShiftAt = game.gameNow();
        game.updateGame();
        check(game.shiftPending && game.shiftNumber == 0 && Arrays.equals(slots, game.slotOfRoom), "Hallway shift was not deferred");
        int[] room = game.roomTile(0, 1, 1); game.playerX = room[0]; game.playerY = room[1];
        game.updateGame();
        check(!game.shiftPending && game.shiftNumber == 1, "Queued shift did not execute on entering a room");
        game.updateGame(); check(game.shiftNumber == 1, "Queued shift burst into multiple shifts");
        Main.Door other = null;
        for (Main.Door[] doors : game.roomDoors) for (Main.Door door : doors) {
            if (door.active && door.roomId != 0) other = door;
        }
        Main.Item hallwayItem = new Main.Item(Main.ItemType.BOTTLE, other.x, other.y);
        game.items.add(hallwayItem);
        Set<Main.Item> items = identities(game);
        for (int i = 0; i < 100; i++) {
            game.shiftRooms();
            check(items.equals(identities(game)) && !game.isWall(hallwayItem.x, hallwayItem.y),
                    "Hallway item lost or stranded");
        }
        System.out.println("PASS hallway deferral, one queued shift, and hallway-item preservation");
    }
    static Main.Door firstDoor(Main game) {
        for (Main.Door[] doors : game.roomDoors) for (Main.Door door : doors) if (door.active) return door;
        throw new AssertionError("No room doors");
    }
    static int[] faceDoor(Main game, Main.Door door) {
        int dx = door.side == 1 ? 1 : door.side == 3 ? -1 : 0;
        int dy = door.side == 2 ? 1 : door.side == 0 ? -1 : 0;
        game.playerX = door.x - dx; game.playerY = door.y - dy;
        game.faceX = dx; game.faceY = dy;
        return new int[] {dx, dy};
    }
    static void doors() {
        Clock clock = new Clock(); Main game = clock.game; Main.Door door = firstDoor(game);
        int[] direction = faceDoor(game, door);
        game.monsterX = door.x + direction[0]; game.monsterY = door.y + direction[1];
        game.toggleDoor();
        check(!door.open && game.isWall(door.x, door.y), "Door did not block collision");
        check(!game.lineClear(game.playerX, game.playerY, game.monsterX, game.monsterY, 7), "Closed door does not block sight");
        check(game.monsterStunnedUntil == game.gameNow() + Main.DOOR_STUN_MS, "Timed slam did not stun");
        int px = game.playerX, py = game.playerY;
        game.movePlayer(direction[0], direction[1], false);
        check(game.playerX == px && game.playerY == py, "Player walked through closed door");
        Point through = game.findNextStep(game.monsterX, game.monsterY, game.playerX, game.playerY);
        check(through != null && through.x == door.x && through.y == door.y, "Monster cannot plan through closed door");
        check(!game.stepMonster(through), "Stunned monster moved");
        clock.advance(Main.DOOR_STUN_MS); game.stepMonster(through);
        clock.advance(Main.DOOR_FORCE_MS - 1); game.stepMonster(through);
        check(!door.open, "Monster forced door too early");
        clock.advance(1); game.stepMonster(through);
        check(door.open, "Monster cannot force closed door");
        long oldStun = game.monsterStunnedUntil;
        game.toggleDoor(); check(game.monsterStunnedUntil == oldStun, "Door stun cooldown bypassed");
        game.toggleDoor(); clock.advance(Main.DOOR_STUN_COOLDOWN_MS);
        game.toggleDoor(); check(game.monsterStunnedUntil > oldStun, "Door stun does not recover");
        door.open = true; game.monsterX = door.x; game.monsterY = door.y;
        game.toggleDoor(); check(door.open, "Door closed onto monster");
        game.monsterX = Main.COLS - 2; game.monsterY = Main.ROWS - 2;
        game.playerX = door.x; game.playerY = door.y;
        game.toggleDoor(); check(door.open, "Door closed onto player");
        faceDoor(game, door);
        key(game, KeyEvent.VK_SPACE, false); boolean state = door.open;
        key(game, KeyEvent.VK_SPACE, false); check(door.open == state, "Held Space repeatedly toggles door");
        key(game, KeyEvent.VK_SPACE, true); key(game, KeyEvent.VK_SPACE, false);
        check(door.open != state, "Fresh Space press does not toggle door");
        key(game, KeyEvent.VK_SPACE, true);
        state = door.open;
        key(game, KeyEvent.VK_SPACE, false, InputEvent.SHIFT_DOWN_MASK);
        check(door.open != state, "Cannot operate a door while holding run");
        System.out.println("PASS highlighted-door state, blocking, sight, slam, cooldown, force-open, occupancy, key repeat");
    }
    static void mapAndInput() {
        Clock clock = new Clock(); Main game = clock.game;
        key(game, KeyEvent.VK_M, false);
        check(game.viewMode == Main.ViewMode.MAP, "M did not open map");
        long closes = game.mapClosesAt;
        int px = game.playerX, py = game.playerY;
        Set<Main.Item> items = identities(game); Main.Item held = game.held;
        IdentityHashMap<Main.Door, Boolean> doors = new IdentityHashMap<>();
        for (Main.Door[] group : game.roomDoors) for (Main.Door door : group) doors.put(door, door.open);
        for (int code : new int[] {KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_Q, KeyEvent.VK_F,
                KeyEvent.VK_R, KeyEvent.VK_P, KeyEvent.VK_SPACE}) key(game, code, false);
        game.grabItem(); game.throwItem(); game.toggleLocker(); game.toggleDoor(); game.openWarningSettings();
        check(game.playerX == px && game.playerY == py && game.held == held && !game.hidden
                && identities(game).equals(items) && !game.warningSettingsOpen, "Map permits interaction or movement");
        for (Main.Door door : doors.keySet()) check(door.open == doors.get(door), "Map permits door use");
        int[] monster = game.roomTile(0, 5, 1); game.monsterX = monster[0]; game.monsterY = monster[1];
        int mx = game.monsterX, my = game.monsterY;
        clock.advance(500); game.updateGame();
        check(game.monsterX != mx || game.monsterY != my, "Map pauses monster movement");
        key(game, KeyEvent.VK_M, false); check(game.mapClosesAt == closes, "Held M extends map duration");
        game.monsterX = Main.COLS - 2; game.monsterY = Main.ROWS - 2;
        clock.advance(9499); game.updateGame(); check(game.viewMode == Main.ViewMode.MAP, "Map closed before 10 seconds");
        clock.advance(1); game.updateGame();
        check(game.viewMode == Main.ViewMode.PLAYING && game.mapCooldownRemaining() == 15000, "Map duration/cooldown is wrong");
        key(game, KeyEvent.VK_W, false); game.updatePlayerMovement();
        check(game.playerX == px && game.playerY == py, "Held movement resumes after map closes");
        key(game, KeyEvent.VK_R, false); check(identities(game).equals(items), "Held restart fires after map closes");
        clock.advance(15000); key(game, KeyEvent.VK_M, false);
        check(game.viewMode == Main.ViewMode.PLAYING, "Held M reopens map after cooldown");
        key(game, KeyEvent.VK_M, true); key(game, KeyEvent.VK_M, false);
        check(game.viewMode == Main.ViewMode.MAP, "Map does not reopen at 25 seconds");
        key(game, KeyEvent.VK_ESCAPE, false); check(game.viewMode == Main.ViewMode.PLAYING, "Esc does not close map first");
        key(game, KeyEvent.VK_ESCAPE, false); check(game.viewMode == Main.ViewMode.PLAYING, "Held Esc also opens pause");
        key(game, KeyEvent.VK_ESCAPE, true); key(game, KeyEvent.VK_ESCAPE, false);
        check(game.viewMode == Main.ViewMode.PAUSE, "Fresh Esc does not open pause");
        key(game, KeyEvent.VK_ESCAPE, true);
        key(game, KeyEvent.VK_ESCAPE, false, InputEvent.SHIFT_DOWN_MASK);
        check(game.viewMode == Main.ViewMode.PLAYING, "Holding run prevents Esc resume");
        key(game, KeyEvent.VK_M, true);
        clock.advance(Main.MAP_COOLDOWN_MS);
        key(game, KeyEvent.VK_M, false, InputEvent.SHIFT_DOWN_MASK);
        check(game.viewMode == Main.ViewMode.MAP, "Cannot open map while holding run");
        System.out.println("PASS 10-second map, 25-second activation cooldown, full input lock, live monster, and fresh-key rules");
    }
    static void pauseAndContinue() {
        Clock clock = new Clock(); Main game = clock.game;
        game.toggleMap(); game.closeMap();
        game.monsterStunnedUntil = game.gameNow() + 2000;
        long shiftAt = game.nextShiftAt, cooldown = game.mapCooldownRemaining(), time = game.gameNow();
        int px = game.playerX, py = game.playerY;
        game.handleEscape(); clock.advance(60000); game.updateGame();
        check(game.gameNow() == time && game.nextShiftAt == shiftAt && game.mapCooldownRemaining() == cooldown,
                "Pause consumes shift/map time");
        check(game.monsterStunnedUntil - game.gameNow() == 2000, "Pause consumes stun time");
        game.handleEscape(); check(game.viewMode == Main.ViewMode.PLAYING && game.gameNow() == time, "Resume jumps clock");
        Main.GameSession session = new Main.GameSession(null); session.run = game; game.ownerSession = session;
        Set<Main.Item> objects = identities(game);
        Main.Door door = firstDoor(game); door.open = false;
        session.showMenu(); clock.advance(300000);
        check(session.canContinue() && game.viewMode == Main.ViewMode.MENU && game.gameNow() == time,
                "Main menu loses or advances run");
        check(!game.gameTimer.isRunning() && !game.movementTimer.isRunning(), "Main menu leaves timers running");
        Main.MainMenuPanel menu = new Main.MainMenuPanel(null, session);
        check(findButton(menu, "CONTINUE").isEnabled(), "Continue is disabled for retained run");
        session.continueGame();
        check(session.run == game && game.viewMode == Main.ViewMode.PLAYING && game.gameNow() == time,
                "Continue replaced run or advanced timers");
        check(game.playerX == px && game.playerY == py && identities(game).equals(objects) && !door.open,
                "Continue changed position, contents, or door state");
        game.gameOver = true; check(!session.canContinue(), "Continue resumes finished run");
        System.out.println("PASS Esc pause, frozen clocks, in-memory Continue, object/state preservation, timer cleanup");
    }
    static JButton findButton(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) return button;
            if (component instanceof Container nested) {
                JButton result = findButton(nested, text); if (result != null) return result;
            }
        }
        return null;
    }
    static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    static BufferedImage render(Main game) {
        game.setSize(1280, 900); layout(game);
        BufferedImage image = new BufferedImage(1280, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); game.paint(g); g.dispose(); return image;
    }
    static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
    static void rememberedMap(File previews) throws Exception {
        Clock clock = new Clock(); Main game = clock.game;
        check(Arrays.stream(game.journal.rooms).filter(Objects::nonNull).count() == 1, "Unexplored rooms revealed at start");
        game.toggleMap(); BufferedImage before = render(game);
        game.monsterX = game.playerX + 1; game.monsterY = game.playerY;
        check(Arrays.equals(pixels(before), pixels(render(game))), "Map pixels reveal monster location");
        ImageIO.write(before, "png", new File(previews, "map-unexplored.png"));
        game.closeMap();
        for (int room = 0; room < Main.ROOM_COUNT; room++) game.journal.observeRoom(room, game.slotOfRoom[room], game.conns, 0);
        Main.RoomMemory[] memories = game.journal.rooms.clone();
        game.monsterX = game.waypoints[4][0]; game.monsterY = game.waypoints[4][1];
        game.shiftRooms();
        for (int slot = 1; slot < Main.ROOM_COUNT; slot++) {
            check(game.journal.rooms[slot].equals(memories[slot]), "Shift magically refreshes remembered rooms");
        }
        check(game.journal.stale(game.shiftNumber), "Map does not mark outdated knowledge");
        int actualSlot = game.slotOfRoom[2]; int[] room = game.roomTile(2, 1, 1);
        game.playerX = room[0]; game.playerY = room[1]; game.observeArea();
        check(game.journal.rooms[actualSlot].roomId() == 2, "Revisit does not update room memory");
        check(Arrays.stream(game.journal.rooms).filter(m -> m != null && m.roomId() == 2).count() == 1,
                "Moved room duplicated on remembered map");
        clock.advance(25000); game.toggleMap(); ImageIO.write(render(game), "png", new File(previews, "map-explored-stale.png"));
        game.closeMap(); ImageIO.write(render(game), "png", new File(previews, "doors-world.png"));
        game.handleEscape(); ImageIO.write(render(game), "png", new File(previews, "pause-menu.png"));
        System.out.println("PASS hidden unexplored rooms, enemy-free map pixels, stale memory, and revisit updates");
    }
    public static void main(String[] args) throws Exception {
        File previews = new File(args.length == 0 ? "build/facility-previews" : args[0]); previews.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            try {
                roomConservation(); hallwayDeferralAndItems(); doors(); mapAndInput(); pauseAndContinue(); rememberedMap(previews);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("Facility gameplay checks passed: " + checks);
    }
}
