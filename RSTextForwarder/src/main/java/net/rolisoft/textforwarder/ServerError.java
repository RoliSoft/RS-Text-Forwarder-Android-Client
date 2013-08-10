package net.rolisoft.textforwarder;

import org.json.JSONObject;

public class ServerError extends Exception {

    public JSONObject response;

    public ServerError() { super(); }
    public ServerError(String message) { super(message); }
    public ServerError(String message, Throwable cause) { super(message, cause); }
    public ServerError(Throwable cause) { super(cause); }

    public ServerError(String message, JSONObject response)
    {
        super(message);
        this.response = response;
    }

}
