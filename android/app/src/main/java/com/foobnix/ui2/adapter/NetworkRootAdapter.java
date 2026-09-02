package com.foobnix.ui2.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.opds.Entry;
import com.foobnix.pdf.info.R;
import com.foobnix.webdav.WebDavAdapter;
import com.foobnix.webdav.WebDavItem;

import java.util.List;

/**
 * Root view of the Network page: two sections stacked in one list - OPDS
 * (header, settings row, catalog entries, defaults/faq rows) and WebDAV
 * (header, server rows). The rows are delegated to the existing
 * {@link EntryAdapter} / {@link WebDavAdapter} so their click/remove
 * behaviour stays unchanged. This class lives in the UI layer and may
 * reference both modules; the webdav package itself keeps no OPDS imports.
 */
public class NetworkRootAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_OPDS_HEADER = 0;
    public static final int TYPE_OPDS_SETTINGS = 1;
    public static final int TYPE_ENTRY = 2;
    public static final int TYPE_OPDS_DEFAULTS = 3;
    public static final int TYPE_OPDS_FAQ = 4;
    public static final int TYPE_WEBDAV_HEADER = 5;
    public static final int TYPE_WEBDAV_SERVER = 6;

    private final EntryAdapter entryAdapter;
    private final WebDavAdapter webDavAdapter;

    private Runnable onAddOpds;
    private Runnable onAddWebDav;
    private Runnable onOpdsSettings;
    private Runnable onRestoreDefaults;
    private Runnable onOpenFaq;

    public NetworkRootAdapter(EntryAdapter entryAdapter, WebDavAdapter webDavAdapter) {
        this.entryAdapter = entryAdapter;
        this.webDavAdapter = webDavAdapter;
        // Forward data changes from the delegated child adapters (including
        // future notifyItemRemoved/Changed calls) to this root adapter so the
        // combined list stays consistent. We use a full notifyDataSetChanged
        // because section offsets shift as the entry/server counts change.
        RecyclerView.AdapterDataObserver forwarder = new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { notifyDataSetChanged(); }
            @Override public void onItemRangeChanged(int positionStart, int itemCount) { notifyDataSetChanged(); }
            @Override public void onItemRangeInserted(int positionStart, int itemCount) { notifyDataSetChanged(); }
            @Override public void onItemRangeRemoved(int positionStart, int itemCount) { notifyDataSetChanged(); }
            @Override public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) { notifyDataSetChanged(); }
        };
        entryAdapter.registerAdapterDataObserver(forwarder);
        webDavAdapter.registerAdapterDataObserver(forwarder);
    }

    public void setOnAddOpds(Runnable onAddOpds) {
        this.onAddOpds = onAddOpds;
    }

    public void setOnAddWebDav(Runnable onAddWebDav) {
        this.onAddWebDav = onAddWebDav;
    }

    public void setOnOpdsSettings(Runnable onOpdsSettings) {
        this.onOpdsSettings = onOpdsSettings;
    }

    public void setOnRestoreDefaults(Runnable onRestoreDefaults) {
        this.onRestoreDefaults = onRestoreDefaults;
    }

    public void setOnOpenFaq(Runnable onOpenFaq) {
        this.onOpenFaq = onOpenFaq;
    }

    public void setOpdsEntries(List<Entry> entries) {
        // Replace in place instead of EntryAdapter.clearItems(): clearItems()
        // fires the child's own notifyDataSetChanged, which the forwarding
        // observer registered in the constructor would turn into a second
        // root refresh. Mutating the backing list directly avoids that.
        List<Entry> list = entryAdapter.getItemsList();
        list.clear();
        list.addAll(entries);
        notifyDataSetChanged();
    }

    public void setWebDavServers(List<WebDavItem> servers) {
        // setItems() fires the child's notifyDataSetChanged, which the
        // forwarding observer turns into a root refresh - no extra notify here.
        webDavAdapter.setItems(servers);
    }

    @Override
    public int getItemCount() {
        // header, settings, entries, defaults, faq, webdav header, webdav servers
        return 5 + entryAdapter.getItemCount() + webDavAdapter.getItemCount();
    }

    @Override
    public int getItemViewType(int position) {
        int entryCount = entryAdapter.getItemCount();
        if (position == 0) {
            return TYPE_OPDS_HEADER;
        }
        if (position == 1) {
            return TYPE_OPDS_SETTINGS;
        }
        if (position < 2 + entryCount) {
            return TYPE_ENTRY;
        }
        if (position == 2 + entryCount) {
            return TYPE_OPDS_DEFAULTS;
        }
        if (position == 3 + entryCount) {
            return TYPE_OPDS_FAQ;
        }
        if (position == 4 + entryCount) {
            return TYPE_WEBDAV_HEADER;
        }
        return TYPE_WEBDAV_SERVER;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case TYPE_ENTRY:
                return entryAdapter.onCreateViewHolder(parent, 0);
            case TYPE_WEBDAV_SERVER:
                return webDavAdapter.onCreateViewHolder(parent, 0);
            case TYPE_OPDS_HEADER:
            case TYPE_WEBDAV_HEADER:
                return new HeaderHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.network_section_header, parent, false));
            case TYPE_OPDS_SETTINGS:
                return new SettingsHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.network_settings_row, parent, false));
            default:
                return new TextRowHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.network_text_row, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        int entryCount = entryAdapter.getItemCount();
        switch (getItemViewType(position)) {
            case TYPE_OPDS_HEADER:
                HeaderHolder opdsHeader = (HeaderHolder) holder;
                opdsHeader.title.setText(R.string.opds);
                opdsHeader.plus.setContentDescription(holder.itemView.getContext().getString(R.string.cd_add_new_opds_catalog));
                opdsHeader.plus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (onAddOpds != null) {
                            onAddOpds.run();
                        }
                    }
                });
                break;
            case TYPE_OPDS_SETTINGS:
                holder.itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (onOpdsSettings != null) {
                            onOpdsSettings.run();
                        }
                    }
                });
                break;
            case TYPE_ENTRY:
                entryAdapter.onBindViewHolder(holder, position - 2);
                break;
            case TYPE_OPDS_DEFAULTS:
                ((TextRowHolder) holder).row.setText(R.string.restore_defaults_short);
                ((TextRowHolder) holder).row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (onRestoreDefaults != null) {
                            onRestoreDefaults.run();
                        }
                    }
                });
                break;
            case TYPE_OPDS_FAQ:
                ((TextRowHolder) holder).row.setText(R.string.what_is_the_opds_online_catalog_);
                ((TextRowHolder) holder).row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (onOpenFaq != null) {
                            onOpenFaq.run();
                        }
                    }
                });
                break;
            case TYPE_WEBDAV_HEADER:
                HeaderHolder webDavHeader = (HeaderHolder) holder;
                webDavHeader.title.setText(R.string.webdav);
                webDavHeader.plus.setContentDescription(holder.itemView.getContext().getString(R.string.add_webdav_server));
                webDavHeader.plus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (onAddWebDav != null) {
                            onAddWebDav.run();
                        }
                    }
                });
                break;
            default:
                webDavAdapter.onBindViewHolder((WebDavAdapter.Holder) holder, position - 5 - entryCount);
                break;
        }
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        TextView title;
        ImageView plus;

        HeaderHolder(View v) {
            super(v);
            title = (TextView) v.findViewById(R.id.sectionTitle);
            plus = (ImageView) v.findViewById(R.id.sectionPlus);
        }
    }

    static class SettingsHolder extends RecyclerView.ViewHolder {
        SettingsHolder(View v) {
            super(v);
        }
    }

    static class TextRowHolder extends RecyclerView.ViewHolder {
        TextView row;

        TextRowHolder(View v) {
            super(v);
            row = (TextView) v.findViewById(R.id.textRow);
        }
    }
}
