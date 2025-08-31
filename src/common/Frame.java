package common;

public class Frame {
    public MessageType type;
    public String sender;    
    public String recipient; 
    public String body;      

    public Frame(MessageType type, String sender, String recipient, String body){
        this.type = type;
        this.sender = sender;
        this.recipient = recipient;
        this.body = body;
    }

    public static Frame register(String name){
        return new Frame(MessageType.REGISTER, name, "", "");
    }

    public static Frame dm(String from, String to, String text){
        return new Frame(MessageType.DM, from, to, text);
    }

    public static Frame ack(String text){
        return new Frame(MessageType.ACK, "", "", text);
    }

    public static Frame error(String text){
        return new Frame(MessageType.ERROR, "", "", text);
    }
}