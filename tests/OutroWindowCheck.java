package com.mycompany.deadsignal;

import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.layout.StackPane;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

/** Real successful-exit -> playing outro -> Skip/end -> main-menu integration. */
public final class OutroWindowCheck {
    static JFrame frame;static Main.GameSession session;
    static final Font FONT=new Font("Monospaced",Font.BOLD,15);
    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(()->{
                frame=new JFrame("Outro integration check");frame.setBounds(-20000,-20000,1000,700);
                session=new Main.GameSession(frame);session.showMenu();frame.setVisible(true);
            });
            for(int iteration=0;iteration<2;iteration++) {
                SwingUtilities.invokeAndWait(()->{
                    Main game=new Main();game.ownerSession=session;session.run=game;session.show(game);
                    RoomsOutroCheck.stepIntoExit(game,true);
                    if(!game.won || !game.outroStarted || game.gameTimer.isRunning() || game.movementTimer.isRunning())
                        throw new AssertionError("Winning run did not stop for its outro");
                });
                MediaPlayer player=awaitVideo();
                if(iteration==0)SwingUtilities.invokeAndWait(()->((IntroVideoPanel)frame.getContentPane()).skipButton.doClick(0));
                else Platform.runLater(()->player.seek(player.getMedia().getDuration().subtract(Duration.millis(250))));
                awaitMenu();
                CompletableFuture<Boolean> disposed=new CompletableFuture<>();
                Platform.runLater(()->disposed.complete(player.getStatus()==MediaPlayer.Status.DISPOSED));
                if(!disposed.get(3,TimeUnit.SECONDS))throw new AssertionError("Outro player status after menu: "+player.getStatus());
                System.out.println(iteration==0?"Native outro: successful exit, video playing, Skip returns to menu.":
                        "Native outro: another successful run, natural end returns to menu.");
            }
        } finally {
            SwingUtilities.invokeAndWait(()->{if(frame!=null)frame.dispose();});
            try{Platform.exit();}catch(IllegalStateException ignored){}
        }
    }
    static MediaPlayer awaitVideo() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            AtomicReference<JFXPanel> surface=new AtomicReference<>();
            SwingUtilities.invokeAndWait(()->{
                if(!(frame.getContentPane() instanceof IntroVideoPanel video))throw new AssertionError("Win did not show an outro");
                if(!"Skip outro video".equals(video.skipButton.getAccessibleContext().getAccessibleName()))throw new AssertionError("Wrong cutscene");
                for(Component c:video.getComponents())if(c instanceof JFXPanel panel)surface.set(panel);
            });
            if(surface.get()!=null) {
                CompletableFuture<MediaPlayer> ready=new CompletableFuture<>();
                Platform.runLater(()->{
                    try{
                        if(surface.get().getScene()==null){ready.complete(null);return;}
                        StackPane root=(StackPane)surface.get().getScene().getRoot();
                        MediaPlayer player=((MediaView)root.getChildren().get(0)).getMediaPlayer();player.setMute(true);
                        ready.complete(player.getStatus()==MediaPlayer.Status.PLAYING && player.getCurrentTime().toMillis()>=150?player:null);
                    }catch(Throwable error){ready.completeExceptionally(error);}
                });
                MediaPlayer player=ready.get(3,TimeUnit.SECONDS);
                if(player!=null){
                    if(!player.getMedia().getSource().endsWith("outro.mp4"))throw new AssertionError("Wrong media source");
                    return player;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Outro video never played");
    }
    static void awaitMenu() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            AtomicReference<Boolean> ready=new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(()->{
                if(frame.getContentPane() instanceof Main.MainMenuPanel) {
                    if(session.canContinue())throw new AssertionError("Completed run can Continue");
                    JButton button=findButton(frame.getContentPane(),"CONTINUE");
                    if(button==null || button.isEnabled())throw new AssertionError("Continue is enabled after win");
                    ready.set(true);
                }
            });
            if(ready.get())return;Thread.sleep(50);
        }
        throw new AssertionError("Outro did not return to main menu");
    }
    static JButton findButton(Container root,String text) {
        for(Component child:root.getComponents()) {
            if(child instanceof JButton button && text.equals(button.getText()))return button;
            if(child instanceof Container nested){JButton found=findButton(nested,text);if(found!=null)return found;}
        }
        return null;
    }
}
