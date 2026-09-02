package com.foobnix.ui2;

import android.content.Context;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.android.utils.Dips;

import java.util.HashSet;
import java.util.Set;

/**
 * Moon+ Reader style shelf for the library bookshelf (书架 mode). The walnut
 * base is drawn INSIDE this decoration (content-anchored shader) so the wood
 * scrolls together with the books — a view background could not scroll. Under
 * every row a thick plank front-face is drawn with a shadow band above and
 * lit/dark edges, so the covers appear to stand on shelves.
 */
public class ShelfBoardsDecoration extends RecyclerView.ItemDecoration {

    private final int boardH;
    private final int shadowH;
    private final Paint base = new Paint();
    private final Paint board = new Paint();
    private final Paint edge = new Paint();
    private final Paint shadow = new Paint();
    private final Set<Integer> rows = new HashSet<Integer>();

    public ShelfBoardsDecoration(Context c) {
        boardH = Dips.dpToPx(20);
        shadowH = Dips.dpToPx(9);
        final BitmapShader walnut = new BitmapShader(WoodShelf.tile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        // content-anchored: the grain moves with the list
        base.setShader(walnut);
        board.setShader(walnut);
        shadow.setShader(new LinearGradient(0, 0, 0, shadowH, 0x00000000, 0x38000000, Shader.TileMode.CLAMP));
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        outRect.bottom = shadowH + boardH;
    }

    @Override
    public void onDraw(Canvas c, RecyclerView parent, RecyclerView.State state) {
        // scrolling wood over the whole visible area
        final Rect clip = c.getClipBounds();
        if (clip != null) {
            c.drawRect(clip, base);
        }
        final int width = parent.getWidth();
        final int edgeLight = Dips.dpToPx(2);
        final int edgeDark = Dips.dpToPx(3);
        rows.clear();
        for (int i = 0; i < parent.getChildCount(); i++) {
            final View child = parent.getChildAt(i);
            if (!rows.add(child.getBottom())) {
                continue;
            }
            final int top = child.getBottom();
            // books cast a soft shadow onto the plank below them
            c.drawRect(0, top, width, top + shadowH, shadow);
            // plank front face: same walnut, darkened downward for depth
            final int faceTop = top + shadowH;
            c.drawRect(0, faceTop, width, faceTop + boardH, board);
            edge.setShader(new LinearGradient(0, faceTop, 0, faceTop + boardH,
                    0x00000000, 0x5C000000, Shader.TileMode.CLAMP));
            c.drawRect(0, faceTop, width, faceTop + boardH, edge);
            edge.setShader(null);
            // lit top edge and dark bottom edge of the board
            edge.setColor(0xFFC29A6E);
            c.drawRect(0, faceTop, width, faceTop + edgeLight, edge);
            edge.setColor(0xFF4A3018);
            c.drawRect(0, faceTop + boardH - edgeDark, width, faceTop + boardH, edge);
        }
    }
}
