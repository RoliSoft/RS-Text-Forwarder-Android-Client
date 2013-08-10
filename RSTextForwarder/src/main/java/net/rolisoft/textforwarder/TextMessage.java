package net.rolisoft.textforwarder;

public class TextMessage {

    double date;
    String from, body;

    public TextMessage(String from, double date, String body)
    {
        this.from = from;
        this.date = date;
        this.body = body;
    }

    public void append(String body)
    {
        this.body += body;
    }

}
