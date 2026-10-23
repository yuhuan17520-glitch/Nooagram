import android.graphics.RectF;
import android.view.MotionEvent;

import com.radolyn.ayugram.ui.cells.DeletedMessagesColorPickerCell;

import java.util.ArrayList;
import java.util.List;

public final class DeletedColorTouchProbe {
    private DeletedColorTouchProbe() {
    }

    public static void verify() {
        CaptureParent parent = new CaptureParent();
        DeletedMessagesColorPickerCell cell = newCell(parent);
        require(!cell.onTouchEvent(MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN)),
                "A touch outside the color buttons was consumed");
        require(!parent.disallow, "A touch outside the color buttons claimed the parent");

        parent = new CaptureParent();
        cell = newCell(parent);
        List<Integer> selected = new ArrayList<>();
        cell.setOnColorSelected(selected::add);
        require(cell.onTouchEvent(MotionEvent.obtain(183, 38, MotionEvent.ACTION_DOWN)),
                "A color touch was not captured");
        require(!parent.disallow, "A color touch claimed the parent before slop");
        require(!cell.onTouchEvent(MotionEvent.obtain(183, 60, MotionEvent.ACTION_MOVE)),
                "A vertical drag remained captured by the color cell");
        require(!parent.disallow, "A vertical drag still disallowed parent scrolling");
        cell.onTouchEvent(MotionEvent.obtain(183, 60, MotionEvent.ACTION_CANCEL));
        require(selected.isEmpty(), "A vertical drag selected a color");

        parent = new CaptureParent();
        cell = newCell(parent);
        cell.setSelectedColorId(2, false);
        selected = new ArrayList<>();
        cell.setOnColorSelected(selected::add);
        cell.onTouchEvent(MotionEvent.obtain(183, 38, MotionEvent.ACTION_DOWN));
        cell.onTouchEvent(MotionEvent.obtain(194, 38, MotionEvent.ACTION_MOVE));
        require(parent.disallow, "A horizontal drag did not claim the color gesture");
        require(selected.isEmpty(), "A horizontal drag committed before ACTION_UP");
        cell.onTouchEvent(MotionEvent.obtain(197, 38, MotionEvent.ACTION_UP));
        require(selected.equals(List.of(0)), "ACTION_UP did not commit the original color: " + selected);
        require(!parent.disallow, "The parent remained locked after ACTION_UP");

        parent = new CaptureParent();
        cell = newCell(parent);
        selected = new ArrayList<>();
        cell.setOnColorSelected(selected::add);
        cell.onTouchEvent(MotionEvent.obtain(183, 38, MotionEvent.ACTION_DOWN));
        cell.onTouchEvent(MotionEvent.obtain(253, 38, MotionEvent.ACTION_MOVE));
        cell.onTouchEvent(MotionEvent.obtain(183, 38, MotionEvent.ACTION_MOVE));
        cell.onTouchEvent(MotionEvent.obtain(183, 38, MotionEvent.ACTION_UP));
        require(selected.isEmpty(), "Leaving and returning to the original color committed it");
        require(!parent.disallow, "Leaving the original color left the parent touch lock enabled");

        parent = new CaptureParent();
        cell = newCell(parent);
        selected = new ArrayList<>();
        cell.setOnColorSelected(selected::add);
        cell.onTouchEvent(MotionEvent.obtain(183, 38, MotionEvent.ACTION_DOWN));
        cell.onTouchEvent(MotionEvent.obtain(203, 38, MotionEvent.ACTION_MOVE));
        require(parent.disallow, "Initial horizontal movement did not claim the gesture");
        require(!cell.onTouchEvent(MotionEvent.obtain(203, 68, MotionEvent.ACTION_MOVE)),
                "Horizontal-to-vertical movement retained the color gesture");
        require(!parent.disallow, "Horizontal-to-vertical movement left the parent touch lock enabled");
        cell.onTouchEvent(MotionEvent.obtain(203, 68, MotionEvent.ACTION_UP));
        require(selected.isEmpty(), "Horizontal-to-vertical movement selected a color");

        parent = new CaptureParent();
        cell = newCell(parent);
        selected = new ArrayList<>();
        cell.setOnColorSelected(selected::add);
        cell.onTouchEvent(MotionEvent.obtain(183, 38, MotionEvent.ACTION_DOWN));
        cell.onTouchEvent(MotionEvent.obtain(253, 38, MotionEvent.ACTION_UP));
        require(selected.isEmpty(), "ACTION_UP over a different color committed");
        require(!parent.disallow, "A cancelled ACTION_UP left the parent touch lock enabled");
    }

    private static DeletedMessagesColorPickerCell newCell(CaptureParent parent) {
        DeletedMessagesColorPickerCell cell = new DeletedMessagesColorPickerCell(new android.content.Context(), null);
        cell.attachTestParent(parent);
        cell.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        );
        return cell;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class CaptureParent extends android.view.ViewParent {
        boolean disallow;

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            this.disallow = disallow;
        }
    }
}
