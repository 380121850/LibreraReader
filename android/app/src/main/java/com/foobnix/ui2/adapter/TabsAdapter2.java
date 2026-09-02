package com.foobnix.ui2.adapter;

import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentStatePagerAdapter;

import com.foobnix.model.AppState;
import com.foobnix.ui2.fragment.UIFragment;

import java.util.List;

public class TabsAdapter2 extends FragmentStatePagerAdapter {

    private List<UIFragment> tabFragments;
    FragmentActivity a;

    // Infinite (looping) swipe: the pager gets N+2 pages — a placeholder clone
    // of the last tab at position 0 and one of the first tab at position N+1,
    // with the real tabs at 1..N. When the user settles on a clone, MainTabs2
    // teleports to the matching real page without animation, so swiping past
    // either edge wraps around like Moon+'s tab bar.
    boolean looping = false;

    public TabsAdapter2(FragmentActivity a, List<UIFragment> tabFragments) {
        super(a.getSupportFragmentManager());
        this.tabFragments = tabFragments;
        this.a = a;

    }

    public void setLooping(boolean looping) {
        // a single tab has nothing to wrap to; guard here too, not only at call sites
        this.looping = looping && tabFragments.size() >= 2;
    }

    public boolean isLooping() {
        return looping;
    }

    public int getRealCount() {
        return tabFragments.size();
    }

    // Virtual pager position -> real tab index (0..N-1).
    public int toReal(int position) {
        if (!looping) {
            return position;
        }
        int n = tabFragments.size();
        return (position - 1 + n) % n;
    }

    // Real tab index -> virtual pager position.
    public int toVirtual(int realIndex) {
        if (!looping) {
            return realIndex;
        }
        return realIndex + 1;
    }

    @Override
    public Fragment getItem(int index) {
        int real = toReal(index);
        // Ghost clones must be separate instances: FragmentStatePagerAdapter
        // adds every page by tag, and the same fragment object at two
        // positions would throw. A blank placeholder is enough — MainTabs2
        // teleports away from it as soon as the swipe settles.
        if (looping && index != real + 1) {
            return new GhostTabFragment();
        }
        return tabFragments.get(real);
    }

    @Override
    public int getCount() {
        return looping ? tabFragments.size() + 2 : tabFragments.size();
    }

    // Real-indexed accessors used by the tab strip and MainTabs2.
    public CharSequence getRealPageTitle(int realIndex) {
        return a.getText((Integer) tabFragments.get(realIndex).getNameAndIconRes().first);
    }

    public int getRealIconResId(int realIndex) {
        return (Integer) tabFragments.get(realIndex).getNameAndIconRes().second;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return getRealPageTitle(toReal(position));
    }

    public int getIconResId(final int position) {
        return getRealIconResId(toReal(position));
    }

    // Deliberate: no per-page view state survives recreation. Fragments are
    // re-attached by the FragmentManager, but offscreen-page state (scroll,
    // query) resets — accepted cost of the finish()+restart theme switch.
    @Override
    public Parcelable saveState() {
        return null;
    }

    @Override
    public void restoreState(Parcelable arg0, ClassLoader arg1) {
    }

    /** Blank page shown while the finger is on an edge clone during wrap. */
    public static class GhostTabFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View v = new View(inflater.getContext());
            // match the pager background so the ghost never flashes in the wrong
            // color (the OLED pager is painted black in MainTabs2)
            if (AppState.get().appTheme == AppState.THEME_DARK_OLED || AppState.get().appTheme == AppState.THEME_DARK) {
                v.setBackgroundColor(android.graphics.Color.BLACK);
            }
            return v;
        }
    }
}
