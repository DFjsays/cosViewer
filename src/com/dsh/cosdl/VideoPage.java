package com.dsh.cosdl;

import java.util.ArrayList;
import java.util.List;

public class VideoPage {
    public final List<VideoItem> items = new ArrayList<VideoItem>();
    public boolean hasMore = false;
    public int totalCnt = 0;
}
