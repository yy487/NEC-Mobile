package org.necmobile;

import java.util.ArrayList;
import java.util.List;

public final class MousePadCheck {
    static final class Input implements MousePad.Sink {
        int x, y, held;
        final List<Integer> clicks = new ArrayList<>();
        public void move(int dx, int dy) { x += dx; y += dy; }
        public void buttons(int mask) { held = mask; }
        public void click(int mask) { clicks.add(mask); }
    }
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Input input = new Input(); MousePad pad = new MousePad(8, input);
        pad.down(7, 100, 100, 0, .5f, .5f);
        for (int i=1;i<=20;i++) pad.move(7, 100 + i * .2f, 100);
        require(input.x == 2);
        pad.up(7, 104, 100, 100, true);
        require(input.clicks.equals(List.of(1)));
        input.clicks.clear();
        pad.down(7,100,100,200,1,1); pad.move(7,130,100); pad.move(7,100,100); pad.up(7,100,100,280,true);
        require(input.clicks.isEmpty());
        int before = input.x;
        pad.down(7,100,100,300,1,1); pad.another(20,200,100);
        pad.up(7,100,100,350,false); pad.up(20,200,100,360,true);
        require(input.x == before && input.clicks.equals(List.of(2)));
        input.clicks.clear();
        pad.down(7,100,100,400,1,1); pad.longPress(); require(input.held == 1);
        pad.move(7,120,110); require(input.held == 1 && input.y == 10);
        pad.up(7,120,110,900,true); require(input.held == 0 && input.clicks.isEmpty());
        pad.down(7,100,100,1000,1,1); pad.longPress(); pad.cancel(); require(input.held == 0);
        pad.down(7,100,100,1200,1,1); pad.move(7,120,100); pad.longPress(); require(input.held == 0); pad.cancel();
        pad.down(7,100,100,1400,1,1); pad.another(20,200,100); pad.move(20,230,100);
        pad.up(20,230,100,1500,false); pad.up(7,100,100,1510,true); require(input.clicks.isEmpty());
        System.out.println("Mouse gestures: fractional motion, tap, two fingers, no false click, drag and cancel passed");
    }
}
