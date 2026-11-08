package com.mycompany.deadsignal;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Procedural layout, artwork visibility, and win-outro transition checks. */
public final class RoomsOutroCheck {
    static int checks;
    static final Font FONT = new Font("Monospaced", Font.BOLD, 15);
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static void visit(Main game, int room) {
        int[] p = game.roomTile(room, 1, 1);
        game.playerX=p[0]; game.playerY=p[1]; game.updateCeilings();
    }
    static BufferedImage backgrounds(Main game, boolean halls) {
        BufferedImage image = new BufferedImage(Main.COLS*Main.TILE, Main.ROWS*Main.TILE,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        if(halls) game.drawHallwayBackgrounds(g); else game.drawRoomBackgrounds(g);
        g.dispose(); return image;
    }
    static int[] pixels(BufferedImage image) {
        return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
    }
    static void roomArt() {
        Main game=new Main();
        check(Main.SpriteAssets.ROOM_BACKGROUNDS.length==Main.ROOM_COUNT,"Missing room image slots");
        for(int iteration=0;iteration<2;iteration++) {
            for(int room=0;room<Main.ROOM_COUNT;room++) {
                BufferedImage art=Main.SpriteAssets.ROOM_BACKGROUNDS[room];
                check(art!=null,"Uploaded room PNG not loaded: "+(room+1));
                check(art.getWidth()==168 && art.getHeight()==112,"Unexpected room image footprint");
                visit(game,room); int[] origin=game.roomTile(room,0,0);
                BufferedImage drawn=backgrounds(game,false);
                BufferedImage actual=drawn.getSubimage(origin[0]*Main.TILE,origin[1]*Main.TILE,168,112);
                check(Arrays.equals(pixels(art),pixels(actual)),"Wrong artwork, clipping, or room identity");
                for(int other=0;other<Main.ROOM_COUNT;other++) {
                    if(other==room)continue;
                    int[] p=game.roomTile(other,1,1);
                    check((drawn.getRGB(p[0]*Main.TILE,p[1]*Main.TILE)>>>24)==0,"Artwork exposes a covered room");
                }
            }
            visit(game,1);
            int previous=game.slotOfRoom[0];
            check(game.shiftRooms() && game.slotOfRoom[0]!=previous,"Room shift did not move Room 1");
        }
        BufferedImage saved=Main.SpriteAssets.ROOM_BACKGROUNDS[2];
        try {
            Main.SpriteAssets.ROOM_BACKGROUNDS[2]=null; visit(game,2);
            check(Arrays.stream(pixels(backgrounds(game,false))).allMatch(p->(p>>>24)==0),
                    "Missing room artwork did not leave the base floor available");
        } finally { Main.SpriteAssets.ROOM_BACKGROUNDS[2]=saved; }
    }
    static void hallArt() {
        check(Main.SpriteAssets.HALLWAY!=null,"Hallway PNG was not loaded");
        for(boolean horizontal:new boolean[]{true,false}) {
            Main game=new Main(); Main.Connection target=null;
            for(Main.Connection c:game.conns)if(c.open && c.horizontal==horizontal){target=c;break;}
            check(target!=null,"Missing passage orientation");
            int col=target.a%Main.ROOM_COLS,row=target.a/Main.ROOM_COLS;
            int x=horizontal?game.roomX0[col]+Main.ROOM_W:game.roomX0[col]+target.offset;
            int y=horizontal?game.roomY0[row]+target.offset:game.roomY0[row]+Main.ROOM_H;
            game.playerX=x;game.playerY=y;game.updateCeilings();
            BufferedImage image=backgrounds(game,true);
            check((image.getRGB(x*Main.TILE+Main.TILE/2,y*Main.TILE+Main.TILE/2)>>>24)>0,
                    "Passage artwork missing: "+horizontal);
            boolean clipped=true;
            for(int py=0;py<image.getHeight();py++)for(int px=0;px<image.getWidth();px++) {
                if((image.getRGB(px,py)>>>24)>0 && (game.map[py/Main.TILE][px/Main.TILE]!='h'
                        || game.isCovered(px/Main.TILE,py/Main.TILE)))clipped=false;
            }
            check(clipped,"Hall art covers walls or reveals hidden floor");
            target.open=false;game.applyLayout();game.rebuildDoors();visit(game,0);
            check((backgrounds(game,true).getRGB(x*Main.TILE+Main.TILE/2,y*Main.TILE+Main.TILE/2)>>>24)==0,
                    "Sealed passage still displays artwork");
        }
    }
    static void stepIntoExit(Main game,boolean key) {
        int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
        for(int[] d:directions) {
            int x=game.exitX-d[0],y=game.exitY-d[1];
            if(game.isWall(x,y))continue;
            game.playerX=x;game.playerY=y;game.hasKey=key;game.playerHasStepped=false;
            int[] monster=game.roomTile(4,1,1);game.monsterX=monster[0];game.monsterY=monster[1];
            game.movePlayer(d[0],d[1],false); return;
        }
        throw new AssertionError("No exit approach");
    }
    static void winFlow() {
        Main.GameSession session=new Main.GameSession(null);
        for(int runNumber=0;runNumber<2;runNumber++) {
            session.startNewGame(FONT);Main game=session.run;
            stepIntoExit(game,false);
            check(!game.won && !game.outroStarted,"Locked exit started an outro");
            game.gameTimer.start();game.movementTimer.start();
            stepIntoExit(game,true);
            check(game.won && game.outroStarted,"Successful exit did not start the outro");
            check(!session.canContinue() && game.viewMode==Main.ViewMode.MENU,"Win did not return to menu");
            check(!game.gameTimer.isRunning() && !game.movementTimer.isRunning(),"Win left simulation running");
            check(game.heldMovementKeys.isEmpty(),"Win left held movement active");
            session.showWinOutro(game);game.updateGame();
            check(game.won && game.outroStarted,"Duplicate win update reset the completed run");
        }
        Main stale=session.run;stale.outroStarted=false;
        session.startNewGame(FONT);Main current=session.run;
        session.showWinOutro(stale);
        check(session.run==current && !stale.outroStarted,"Stale run changed the current session");
        current.gameOver=true;current.updateGame();
        check(!current.outroStarted,"Defeat played the escape outro");
        current.restartGame();
        check(!current.outroStarted && !current.won,"Restart retained outro state");
        current.movePlayer(1,0,false);
        check(!current.outroStarted,"Ordinary movement played the outro");
    }
    static final class FakeVideo implements IntroVideoPanel.Playback {
        final JPanel surface=new JPanel();Runnable end;Consumer<Throwable> fail;Runnable closed;
        int closes;boolean deferred;
        public JComponent component(){return surface;}
        public void play(Runnable started,Runnable ended,Consumer<Throwable> failed){end=ended;fail=failed;started.run();}
        public void close(Runnable after){closes++;closed=after;if(!deferred)after.run();}
    }
    static void outroCallbacks() {
        check(IntroVideoPanel.findSource("outro.mp4")!=null,"Outro was not bundled");
        for(int mode=0;mode<4;mode++) {
            AtomicInteger menus=new AtomicInteger();FakeVideo fake=new FakeVideo();fake.deferred=true;
            IntroVideoPanel panel=new IntroVideoPanel("file:/outro.mp4",FONT,menus::incrementAndGet,s->fake,"outro");
            check("Skip outro video".equals(panel.skipButton.getAccessibleContext().getAccessibleName()),"Wrong Skip accessibility label");
            panel.startPlayback();
            if(mode==0)panel.skipButton.doClick(0);
            if(mode==1)fake.end.run();
            if(mode==2)fake.fail.accept(new IllegalStateException("decoder failure"));
            if(mode==3)panel.loadingTimeout.getActionListeners()[0].actionPerformed(new ActionEvent(panel,0,"timeout"));
            check(menus.get()==0 && fake.closes==1,"Outro did not wait for resource release");
            fake.closed.run();fake.closed.run();fake.end.run();panel.skipButton.doClick(0);
            check(menus.get()==1,"Outro delivered duplicate menu transitions");
            check(!panel.loadingTimeout.isRunning(),"Outro left a loading timeout active");
        }
        AtomicInteger menus=new AtomicInteger();
        IntroVideoPanel missing=new IntroVideoPanel(null,FONT,menus::incrementAndGet,s->{throw new AssertionError();},"outro");
        missing.startPlayback();check(menus.get()==1,"Missing outro did not return to menu");
        AtomicInteger created=new AtomicInteger();
        IntroVideoPanel early=new IntroVideoPanel("file:/outro.mp4",FONT,menus::incrementAndGet,s->{created.incrementAndGet();return new FakeVideo();},"outro");
        early.skipButton.doClick(0);early.startPlayback();
        check(menus.get()==2 && created.get()==0,"Early Skip created a late video player");
    }
    static void previews(File folder) throws Exception {
        Main game=new Main();game.setSize(840,688);
        BufferedImage gallery=new BufferedImage(1056,792,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=gallery.createGraphics();g.setColor(new Color(22,25,31));g.fillRect(0,0,1056,792);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for(int room=0;room<Main.ROOM_COUNT;room++) {
            visit(game,room);int[] origin=game.roomTile(room,0,0);
            BufferedImage scene=new BufferedImage(840,688,BufferedImage.TYPE_INT_RGB);
            Graphics2D sg=scene.createGraphics();game.paint(sg);sg.dispose();
            int x=room%3*352+8,y=room/3*264+32;
            g.setColor(Color.WHITE);g.setFont(FONT);g.drawString("ROOM "+(room+1),x,y-10);
            g.drawImage(scene.getSubimage(origin[0]*Main.TILE,origin[1]*Main.TILE,168,112),x,y,336,224,null);
        }
        g.dispose();ImageIO.write(gallery,"png",new File(folder,"all-rooms-in-game.png"));
        for(boolean horizontal:new boolean[]{true,false}) {
            for(Main.Connection c:game.conns)if(c.open && c.horizontal==horizontal) {
                int col=c.a%Main.ROOM_COLS,row=c.a/Main.ROOM_COLS;
                game.playerX=horizontal?game.roomX0[col]+Main.ROOM_W:game.roomX0[col]+c.offset;
                game.playerY=horizontal?game.roomY0[row]+c.offset:game.roomY0[row]+Main.ROOM_H;
                BufferedImage scene=new BufferedImage(840,688,BufferedImage.TYPE_INT_RGB);
                Graphics2D sg=scene.createGraphics();game.paint(sg);sg.dispose();
                ImageIO.write(scene,"png",new File(folder,horizontal?"hallway-horizontal.png":"hallway-vertical.png"));break;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        File folder=new File(args.length==0?"build/rooms-outro-previews":args[0]);folder.mkdirs();
        SwingUtilities.invokeAndWait(()->{try{roomArt();hallArt();winFlow();outroCallbacks();previews(folder);}catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("Room artwork and outro checks passed: "+checks);
    }
}
