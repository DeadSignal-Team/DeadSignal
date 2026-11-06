package com.mycompany.deadsignal;

import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.IdentityHashMap;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Headless input checks. Run on Swing's event thread without opening a window. */
public final class MovementInputTest {
    private static int checks;

    private static final IdentityHashMap<Main, long[]> clocks = new IdentityHashMap<>();

    private static void advance(Main game, long milliseconds) {
        long[] clock = clocks.get(game);
        clock[0] += milliseconds * 1_000_000L;
        game.updatePlayerMovement();
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            heldMovementUsesGameTiming();
            latestDirectionWinsWithoutDiagonalMovement();
            shiftChangesSpeedWhileMoving();
            blockedStepsDoNotUseCooldown();
            releasedTapsDoNotMoveLater();
            aliasesAndModifierReleasesDoNotStick();
            focusAndGameStateClearHeldInput();
            hidingAndRestartClearHeldInput();
            removedPanelsStopTimers();
        });
        System.out.println("Movement input checks passed: " + checks);
    }

    private static Main game() {
        long[] clock = {1_000_000_000L};
        Main game = new Main(() -> clock[0]);
        clocks.put(game, clock);
        for (char[] row : game.map) {
            Arrays.fill(row, '.');
        }
        game.playerX = 5;
        game.playerY = 5;
        game.monsterX = Main.COLS - 2;
        game.monsterY = Main.ROWS - 2;
        game.keyX = game.exitX = Main.COLS - 3;
        game.keyY = game.exitY = Main.ROWS - 3;
        return game;
    }

    private static void key(Main game, int code, int modifiers, boolean release) {
        KeyStroke stroke = KeyStroke.getKeyStroke(code, modifiers, release);
        Object name = game.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
        Action action = game.getActionMap().get(name);
        check(action != null, "Missing binding for " + stroke);
        action.actionPerformed(new ActionEvent(game, ActionEvent.ACTION_PERFORMED,
                String.valueOf(name)));
    }

    private static void heldMovementUsesGameTiming() {
        Main game = game();
        key(game, KeyEvent.VK_W, 0, false);
        position(game, 5, 4, "first press moves immediately");
        advance(game, 249);
        position(game, 5, 4, "walking respects its cooldown");
        advance(game, 1);
        position(game, 5, 3, "holding repeats without another keyboard event");
        advance(game, 250);
        position(game, 5, 2, "held movement continues at the walking interval");
        check(game.soundQueue.size() == 3, "each successful step emits one sound");
        advance(game, 2000);
        position(game, 5, 1, "a delayed timer does not burst through multiple tiles");
        key(game, KeyEvent.VK_W, 0, true);
        advance(game, 250);
        position(game, 5, 1, "release stops movement");
    }

    private static void latestDirectionWinsWithoutDiagonalMovement() {
        Main game = game();
        key(game, KeyEvent.VK_W, 0, false);
        advance(game, 50);
        key(game, KeyEvent.VK_D, 0, false);
        position(game, 5, 4, "turning cannot bypass the movement cooldown");
        key(game, KeyEvent.VK_W, 0, false);
        advance(game, 200);
        position(game, 6, 4, "old key repeats do not steal the latest direction");
        key(game, KeyEvent.VK_D, 0, true);
        advance(game, 250);
        position(game, 6, 3, "releasing the latest key resumes the other held key");
    }

    private static void shiftChangesSpeedWhileMoving() {
        Main game = game();
        key(game, KeyEvent.VK_W, 0, false);
        advance(game, 139);
        key(game, KeyEvent.VK_SHIFT, InputEvent.SHIFT_DOWN_MASK, false);
        position(game, 5, 4, "run still respects the 140 ms minimum");
        advance(game, 1);
        position(game, 5, 3, "Shift starts running without re-pressing W");
        check(game.lastSoundType == Main.SoundType.RUN, "running emits run noise");
        key(game, KeyEvent.VK_SHIFT, 0, true);
        advance(game, 249);
        position(game, 5, 3, "releasing Shift restores the walk interval");
        advance(game, 1);
        position(game, 5, 2, "walking resumes while W stays held");
        check(game.lastSoundType == Main.SoundType.WALK, "walking emits walk noise");

        Main shifted = game();
        key(shifted, KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK, false);
        position(shifted, 6, 5, "Shift plus arrow starts immediately");
        advance(shifted, 140);
        position(shifted, 7, 5, "Shift plus arrow holds at the run interval");
    }

    private static void blockedStepsDoNotUseCooldown() {
        Main game = game();
        game.map[4][5] = '#';
        key(game, KeyEvent.VK_W, 0, false);
        position(game, 5, 5, "walls block movement");
        check(!game.playerHasStepped, "blocked steps do not start a cooldown");
        check(game.soundQueue.isEmpty(), "blocked steps do not emit footstep noise");
        check(game.lastPlayerMove == 0, "blocked steps do not animate walking");
        key(game, KeyEvent.VK_D, 0, false);
        position(game, 6, 5, "turning away from a wall can move immediately");
        game.map[5][7] = '#';
        advance(game, 250);
        position(game, 6, 5, "a later wall still blocks movement");
        key(game, KeyEvent.VK_S, 0, false);
        position(game, 6, 6, "a later blocked attempt does not renew cooldown");
    }

    private static void releasedTapsDoNotMoveLater() {
        Main game = game();
        key(game, KeyEvent.VK_W, 0, false);
        key(game, KeyEvent.VK_W, 0, true);
        advance(game, 50);
        key(game, KeyEvent.VK_D, 0, false);
        key(game, KeyEvent.VK_D, 0, true);
        advance(game, 500);
        position(game, 5, 4, "released taps are not queued as unwanted later steps");
    }

    private static void aliasesAndModifierReleasesDoNotStick() {
        Main game = game();
        key(game, KeyEvent.VK_W, 0, false);
        key(game, KeyEvent.VK_UP, 0, false);
        key(game, KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK, true);
        advance(game, 250);
        position(game, 5, 3, "releasing W keeps the separately held Up key");
        int modifiers = InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                | InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK
                | InputEvent.ALT_GRAPH_DOWN_MASK;
        key(game, KeyEvent.VK_UP, modifiers, true);
        advance(game, 250);
        position(game, 5, 3, "changed modifiers cannot leave an arrow stuck");
        check(game.heldMovementKeys.isEmpty(), "all physical movement keys released");
    }

    private static void focusAndGameStateClearHeldInput() {
        Main game = game();
        key(game, KeyEvent.VK_W, InputEvent.SHIFT_DOWN_MASK, false);
        game.movementFocusListener.windowLostFocus(null);
        advance(game, 500);
        position(game, 5, 4, "focus loss stops movement");
        check(!game.runningHeld, "focus loss clears Shift");

        for (int state = 0; state < 3; state++) {
            Main stopped = game();
            key(stopped, KeyEvent.VK_D, 0, false);
            stopped.gameOver = state == 0;
            stopped.won = state == 1;
            stopped.hidden = state == 2;
            advance(stopped, 250);
            position(stopped, 6, 5, "inactive gameplay stops movement");
            check(stopped.heldMovementKeys.isEmpty(), "inactive gameplay clears held keys");
            key(stopped, KeyEvent.VK_W, 0, false);
            check(stopped.heldMovementKeys.isEmpty(), "inactive gameplay ignores new presses");
        }
    }

    private static void hidingAndRestartClearHeldInput() {
        Main game = game();
        key(game, KeyEvent.VK_D, 0, false);
        Point locker = new Point(game.lockers[0][0], game.lockers[0][1]);
        game.playerX = locker.x;
        game.playerY = locker.y;
        game.toggleLocker();
        check(game.hidden, "player enters a locker");
        check(game.heldMovementKeys.isEmpty(), "entering a locker clears held keys");
        game.toggleLocker();
        advance(game, 500);
        position(game, locker.x, locker.y, "leaving a locker needs fresh movement input");

        key(game, KeyEvent.VK_D, InputEvent.SHIFT_DOWN_MASK, false);
        game.restartGame();
        int spawnX = game.playerX;
        int spawnY = game.playerY;
        advance(game, 500);
        position(game, spawnX, spawnY, "restart does not keep old movement input");
        check(!game.playerHasStepped && !game.runningHeld, "restart resets movement timing");
    }

    private static void removedPanelsStopTimers() {
        Main game = game();
        check(!game.movementTimer.isRunning() && !game.gameTimer.isRunning(),
                "an unattached game does not run background timers");
        check(game.movementTimer.getDelay() == 16 && game.gameTimer.getDelay() == 50,
                "input polling does not change the monster update cadence");
        key(game, KeyEvent.VK_D, 0, false);
        game.movementTimer.start();
        game.gameTimer.start();
        game.removeNotify();
        check(!game.movementTimer.isRunning() && !game.gameTimer.isRunning(),
                "removing gameplay stops both timers");
        check(game.heldMovementKeys.isEmpty(), "removing gameplay clears held keys");
    }

    private static void position(Main game, int x, int y, String description) {
        check(game.playerX == x && game.playerY == y,
                description + ": expected " + x + "," + y + ", got "
                        + game.playerX + "," + game.playerY);
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
