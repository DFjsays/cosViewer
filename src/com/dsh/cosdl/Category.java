package com.dsh.cosdl;

public class Category {
    public String id = "";
    public String name = "";
    public boolean hasSub = false;

    public Category(String id, String name, boolean hasSub) {
        this.id = id;
        this.name = name;
        this.hasSub = hasSub;
    }
}
