package com.atlasdead.wanderbot.gui;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.PitStreakCatalog;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/** Clean & modern persistent control center for WanderBot. */
public class ClickGuiScreen extends GuiScreen {
    private static final int PANEL_W = 820;
    private static final int PANEL_H = 500;
    private static final int SIDEBAR_W = 170;
    private int left, top;
    private Category category = Category.GENERAL;
    private enum Category { GENERAL, RENDER, COMBAT, NAVIGATION, PIT, DEBUG }

    @Override public void initGui() { recalc(); buttonList.clear(); buildButtons(); }
    private void recalc() { left = Math.max(18, (width-PANEL_W)/2); top=Math.max(18,(height-PANEL_H)/2); }

    private void buildButtons() {
        int x=left+SIDEBAR_W+30, y=top+82;
        if(category==Category.GENERAL){
            toggle(10,x,y,"Bot",WanderBotMod.BOT!=null&&WanderBotMod.BOT.isEnabled(),()->{if(WanderBotMod.BOT!=null)WanderBotMod.BOT.toggle();});
            toggle(11,x,y+42,"Navigation",WanderBotSettings.navigationEnabled,()->setBool("nav"));
            toggle(12,x,y+84,"Combat",WanderBotSettings.combatEnabled,()->setBool("combat"));
            toggle(13,x,y+126,"Megastreak Strategy",WanderBotSettings.megastreakStrategy,()->setBool("mega"));
            toggle(17,x,y+168,"Force Pit Mode",WanderBotSettings.forcePitMode,()->{WanderBotSettings.forcePitMode=!WanderBotSettings.forcePitMode; WanderBotSettings.save();});
            info(15,x,y+212,"K = Bot toggle    Right Shift = GUI");
        } else if(category==Category.RENDER){
            toggle(20,x,y,"Pathfinding",WanderBotSettings.showPath,()->{WanderBotSettings.showPath=!WanderBotSettings.showPath; WanderBotSettings.save();});
            toggle(21,x,y+42,"Target Box",WanderBotSettings.showTarget,()->{WanderBotSettings.showTarget=!WanderBotSettings.showTarget; WanderBotSettings.save();});
            toggle(22,x,y+84,"Target Guide",WanderBotSettings.showTargetGuide,()->{WanderBotSettings.showTargetGuide=!WanderBotSettings.showTargetGuide; WanderBotSettings.save();});
            toggle(23,x,y+126,"HUD",WanderBotSettings.showHud,()->{WanderBotSettings.showHud=!WanderBotSettings.showHud; WanderBotSettings.save();});
            toggle(24,x,y+168,"Debug Dashboard",WanderBotSettings.debugDashboard,()->{WanderBotSettings.debugDashboard=!WanderBotSettings.debugDashboard; WanderBotSettings.save();});
        } else if(category==Category.COMBAT){
            toggle(30,x,y,"Combat Enabled",WanderBotSettings.combatEnabled,()->setBool("combat"));
            cycleDouble(32,x,y+42,"Target Scan",WanderBotSettings.targetScanRange,1.0D,8.0D,32.0D,()->WanderBotSettings.targetScanRange);
            toggle(35,x,y+84,"Force Pit Mode",WanderBotSettings.forcePitMode,()->{WanderBotSettings.forcePitMode=!WanderBotSettings.forcePitMode; WanderBotSettings.save();});
            info(36,x,y+128,"KillAura: Myau handles attack via .t killaura");
            info(37,x,y+150,"Combat auto-toggles KillAura at 3.5 blocks");
        } else if(category==Category.NAVIGATION){
            toggle(40,x,y,"Navigation Enabled",WanderBotSettings.navigationEnabled,()->setBool("nav"));
            cycleInt(41,x,y+42,"Repath Ticks",WanderBotSettings.navRepathTicks,2,40,()->WanderBotSettings.navRepathTicks);
            cycleDouble(42,x,y+84,"Lookahead",WanderBotSettings.lookaheadDistance,0.5D,1.0D,8.0D,()->WanderBotSettings.lookaheadDistance);
            info(43,x,y+140,"Terrain-aware A* / local avoidance / recovery remain enabled.");
        } else if(category==Category.PIT){
            List<PitStreakCatalog.Definition> modes = PitStreakCatalog.ALL_MEGASTREAKS;
            for(int i=0;i<modes.size();i++){
                final String id=modes.get(i).id; final PitStreakCatalog.Definition d=modes.get(i);
                int yy=y+i*38;
                buttonList.add(new ValueButton(50+i,x,yy,360,28,d.displayName+(id.equalsIgnoreCase(WanderBotSettings.megastreakId)?"   ✓":""),()->{WanderBotSettings.megastreakId=id; if(WanderBotMod.BOT!=null)WanderBotMod.BOT.getPit().getStreakControl().setMegastreak(id); WanderBotSettings.save();}));
            }
            info(60,x+390,y,"Selected: "+WanderBotSettings.megastreakId);
        } else {
            info(70,x,y,"Runtime Dashboard");
            if(WanderBotMod.BOT!=null){
                info(71,x,y+42,"State: "+WanderBotMod.BOT.getState());
                info(72,x,y+84,"Pit: "+WanderBotMod.BOT.getPitMode());
                info(73,x,y+126,"Target: "+String.valueOf(WanderBotMod.BOT.getTargetName()));
                info(74,x,y+168,"Streak: "+WanderBotMod.BOT.getLocalStreak()+" / peak "+WanderBotMod.BOT.getPeakStreak());
                info(75,x,y+210,"Target Score: "+String.format("%.1f",WanderBotMod.BOT.getTargetScore()));
                info(76,x,y+252,"Armor: "+WanderBotMod.BOT.getTargetArmor());
            }
        }
    }

    private void setBool(String key){
        if("nav".equals(key)) WanderBotSettings.navigationEnabled=!WanderBotSettings.navigationEnabled;
        if("combat".equals(key)) WanderBotSettings.combatEnabled=!WanderBotSettings.combatEnabled;
        if("mega".equals(key)) WanderBotSettings.megastreakStrategy=!WanderBotSettings.megastreakStrategy;
        WanderBotSettings.save(); initGui();
    }
    private void toggle(int id,int x,int y,String label,boolean val,final Runnable action){
        buttonList.add(new ValueButton(id,x,y,360,28,label+"   "+(val?"ON":"OFF"),action));
    }
    private void cycleDouble(int id,int x,int y,String label,double current,double step,double min,double max,final ValueGetter getter){
        buttonList.add(new ValueButton(id,x,y,360,28,label+"   "+fmt(current),()->{double n=getter.get()+step;if(n>max+1e-6)n=min;setDouble(label,n);}));
    }
    private void cycleInt(int id,int x,int y,String label,int current,int min,int max,final IntGetter getter){
        buttonList.add(new ValueButton(id,x,y,360,28,label+"   "+current,()->{int n=getter.get()+1;if(n>max)n=min;setInt(label,n);}));
    }
    private void setDouble(String label,double v){ if(label.startsWith("Target Scan"))WanderBotSettings.targetScanRange=v; else if(label.startsWith("Lookahead"))WanderBotSettings.lookaheadDistance=v; WanderBotSettings.clamp(); WanderBotSettings.save(); initGui(); }
    private void setInt(String label,int v){ if(label.startsWith("Repath"))WanderBotSettings.navRepathTicks=v; WanderBotSettings.clamp(); WanderBotSettings.save(); initGui(); }
    private interface ValueGetter{double get();} private interface IntGetter{int get();}
    private void info(int id,int x,int y,String text){buttonList.add(new ValueButton(id,x,y,360,28,text,null));}

    @Override protected void actionPerformed(GuiButton b)throws IOException{ if(b instanceof ValueButton && ((ValueButton)b).action!=null){((ValueButton)b).action.run();return;} if(b.id>=100&&b.id<100+Category.values().length){category=Category.values()[b.id-100];initGui();} }
    @Override public void drawScreen(int mx,int my,float pt){drawDefaultBackground();recalc();
        drawRect(left,top,left+PANEL_W,top+PANEL_H,0xF20D1118); drawRect(left,top,left+SIDEBAR_W,top+PANEL_H,0xF211151C); drawRect(left+SIDEBAR_W,top,left+PANEL_W,top+64,0xF2181E27);
        drawString(fontRendererObj,"WANDERBOT",left+22,top+20,0xFFFFFFFF); drawString(fontRendererObj,"Control Center",left+22,top+36,0xFF8C98AA);
        drawString(fontRendererObj,title(),left+SIDEBAR_W+30,top+19,0xFFFFFFFF); drawString(fontRendererObj,subtitle(),left+SIDEBAR_W+30,top+37,0xFF8C98AA);
        for(int i=0;i<Category.values().length;i++){int id=100+i; addCategoryIfMissing(id,left+14,top+82+i*48,category==Category.values()[i]);}
        drawString(fontRendererObj,"K",left+18,top+PANEL_H-28,0xFFD0D7E2); drawString(fontRendererObj,"Bot Toggle",left+36,top+PANEL_H-28,0xFF707C8E); drawString(fontRendererObj,"Right Shift",left+SIDEBAR_W+30,top+PANEL_H-28,0xFFD0D7E2); drawString(fontRendererObj,"ClickGUI",left+SIDEBAR_W+92,top+PANEL_H-28,0xFF707C8E);
        super.drawScreen(mx,my,pt);
    }
    private void addCategoryIfMissing(int id,int x,int y,boolean selected){
        drawRect(x,y,x+SIDEBAR_W-28,y+34,selected?0xFF242C37:0x00000000); if(selected)drawRect(x,y,x+3,y+34,0xFF7E9BFF); drawString(fontRendererObj,Category.values()[id-100].name(),x+14,y+11,selected?0xFFFFFFFF:0xFF98A4B5);
    }
    private String title(){return category==Category.GENERAL?"General":category==Category.RENDER?"Render":category==Category.COMBAT?"Combat":category==Category.NAVIGATION?"Navigation":category==Category.PIT?"Pit":"Debug";}
    private String subtitle(){return category==Category.PIT?"Megastreak selector":category==Category.COMBAT?"Combat + KillAura":category==Category.NAVIGATION?"Pathfinding controls":category==Category.RENDER?"Visuals & telemetry":"Runtime configuration";}
    @Override protected void mouseClicked(int mx,int my,int button)throws IOException{for(int i=0;i<Category.values().length;i++){int y=top+82+i*48;if(mx>=left+14&&mx<=left+SIDEBAR_W-14&&my>=y&&my<=y+34){category=Category.values()[i];initGui();return;}}super.mouseClicked(mx,my,button);}
    @Override public void handleKeyboardInput()throws IOException{super.handleKeyboardInput();if(Keyboard.getEventKeyState()&&Keyboard.getEventKey()==Keyboard.KEY_ESCAPE){WanderBotSettings.save();mc.displayGuiScreen(null);}}
    @Override public void onGuiClosed(){WanderBotSettings.save();super.onGuiClosed();}

    private static String fmt(double v){return String.format("%.2f",v);}
    private static final class ValueButton extends GuiButton{final Runnable action; ValueButton(int id,int x,int y,int w,int h,String text,Runnable action){super(id,x,y,w,h,text);this.action=action;enabled=action!=null;}
        @Override public void drawButton(net.minecraft.client.Minecraft mc,int mx,int my){if(!visible)return;boolean hover=mx>=xPosition&&mx<xPosition+width&&my>=yPosition&&my<yPosition+height;GuiScreen s=mc.currentScreen;if(s!=null)s.drawRect(xPosition,yPosition,xPosition+width,yPosition+height,action==null?0xFF171D25:(hover?0xFF293340:0xFF1C232D));mc.fontRendererObj.drawString(displayString,xPosition+12,yPosition+10,action==null?0xFF8996A8:0xFFE0E6EE);if(action!=null)s.drawRect(xPosition+width-10,yPosition+6,xPosition+width-7,yPosition+height-6,hover?0xFF8AA9FF:0xFF42506A);}}
}
