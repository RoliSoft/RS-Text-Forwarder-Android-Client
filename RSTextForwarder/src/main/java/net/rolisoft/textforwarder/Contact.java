package net.rolisoft.textforwarder;

import java.util.ArrayList;
import java.util.List;

public class Contact {

    public String id;
    public String name;
    public Number preferred;
    public Number selected;
    public List<Number> numbers;

    public Contact(String id, String name)
    {
        this.id = id;
        this.name = name;
        this.preferred = null;
        this.numbers = new ArrayList<Number>();
    }

    public Number addNumber(String number, String type, boolean isDefault)
    {
        Number numObj = new Number(number, type, isDefault);
        this.numbers.add(numObj);
        return numObj;
    }

    public static class Number {

        public String number;
        public String type;
        public boolean isDefault;

        public Number(String number, String type, boolean isDefault)
        {
            this.number = number;
            this.type = type;
            this.isDefault = isDefault;
        }

    }

}
