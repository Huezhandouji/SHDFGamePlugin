package com.sHDFGamePlugin.domain.ticket;

import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.TicketDepletedEvent;

public class TicketManager {

    private static final TicketManager INSTANCE = new TicketManager();

    private int currentTickets;
    private int maxTickets;
    private boolean initialized;

    private TicketManager(){}

    public static TicketManager getInstance(){
        return INSTANCE;
    }

    public void init(int initialTickets, int maxTickets){
        if(initialTickets <= 0) throw new IllegalArgumentException("Initial tickets must be greater than 0.");
        if(maxTickets <= 0) throw new IllegalArgumentException("Maximum tickets must be greater than 0.");
        this.currentTickets = Math.min(initialTickets, maxTickets);
        this.maxTickets = maxTickets;
        this.initialized = true;
    }

    public void reset(){
        currentTickets = 0;
        maxTickets = 0;
        initialized = false;
    }

    public int getCurrentTickets() {
        return currentTickets;
    }

    public int getMaxTickets() {
        return maxTickets;
    }

    public boolean decreaseTicket(int amount){
        if(!initialized) return false;
        currentTickets -= amount;
        if(currentTickets <= 0){
            currentTickets = 0;
            GameEventBus.publish(new TicketDepletedEvent());
        }
        return true;
    }

    public boolean increaseTicket(int amount){
        if(!initialized) return false;
        currentTickets = Math.min(currentTickets + amount, maxTickets);
        return true;
    }

    public boolean isDepleted(){
        return initialized && currentTickets <= 0;
    }

    public  boolean isInitialized(){
        return initialized;
    }

}
