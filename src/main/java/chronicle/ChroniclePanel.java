/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * Side panel with three faces, driven by the plugin's mode:
 * <ul>
 *   <li><b>Off</b> — the master switch is off: just a prompt to enable it.</li>
 *   <li><b>Cloud</b> — the enrolled RSN + push status, the sync actions, and the
 *       self-service privacy section (lock, list/unlist, export, delete), each
 *       authenticated by the account token the plugin already holds.</li>
 *   <li><b>Local</b> — nothing is enrolled or transmitted, so the server-only
 *       controls are hidden; only "Open my page" remains, pointing at the
 *       self-contained page the plugin keeps on disk.</li>
 * </ul>
 */
class ChroniclePanel extends PluginPanel
{
	private static final DateTimeFormatter WHEN =
		DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());

	private final ChroniclePlugin plugin;

	private final JLabel rsnLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final JButton pushNowButton = new JButton("Push stats now");
	private final JButton reEnrolButton = new JButton("Re-enrol this account");
	private final JButton openPageButton = new JButton("Open my page");

	// The whole privacy block, shown/hidden as a unit (cloud only).
	private final JPanel privacySection = new JPanel();
	private final JLabel privacyState = new JLabel();
	private final JButton lockButton = new JButton();
	private final JButton listButton = new JButton();
	private final JButton deleteButton = new JButton();

	ChroniclePanel(ChroniclePlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Chronicle");
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 15f));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(title);
		content.add(vgap(8));

		rsnLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(rsnLabel);
		content.add(vgap(4));
		content.add(statusLabel);
		content.add(vgap(12));

		JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

		pushNowButton.addActionListener(e -> plugin.actionPushNow());
		buttons.add(pushNowButton);

		reEnrolButton.addActionListener(e -> plugin.actionReEnrol());
		buttons.add(reEnrolButton);

		openPageButton.addActionListener(e -> onOpenPage());
		buttons.add(openPageButton);

		content.add(buttons);
		content.add(vgap(14));

		// ── Privacy & data (cloud only) ───────────────────────────────────
		privacySection.setLayout(new BoxLayout(privacySection, BoxLayout.Y_AXIS));
		privacySection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		privacySection.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel privacyTitle = new JLabel("Privacy & data");
		privacyTitle.setFont(privacyTitle.getFont().deriveFont(java.awt.Font.BOLD));
		privacyTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		privacySection.add(privacyTitle);
		privacySection.add(vgap(4));

		privacyState.setAlignmentX(Component.LEFT_ALIGNMENT);
		privacySection.add(privacyState);
		privacySection.add(vgap(8));

		JPanel privacy = new JPanel(new GridLayout(0, 1, 0, 6));
		privacy.setBackground(ColorScheme.DARK_GRAY_COLOR);
		privacy.setAlignmentX(Component.LEFT_ALIGNMENT);

		lockButton.addActionListener(e -> onLockClicked());
		privacy.add(lockButton);

		listButton.addActionListener(e -> plugin.actionSetPublic(!plugin.publicListed()));
		privacy.add(listButton);

		JButton exportButton = new JButton("Export my data");
		exportButton.addActionListener(e -> plugin.actionExport());
		privacy.add(exportButton);

		deleteButton.addActionListener(e -> onDeleteClicked());
		privacy.add(deleteButton);

		privacySection.add(privacy);
		content.add(privacySection);

		add(content, BorderLayout.NORTH);
		update();
	}

	/** The page button: open the cloud profile in a browser (cloud sync only). */
	private void onOpenPage()
	{
		String rsn = plugin.enrolledRsn();
		if (rsn != null && !rsn.isEmpty())
		{
			LinkBrowser.browse(plugin.serverBaseUrl() + "/osrs/" + encode(rsn));
		}
	}

	private void onLockClicked()
	{
		if (plugin.pageLocked())
		{
			int ok = JOptionPane.showConfirmDialog(this,
				"Remove the password lock from your page?\nAnyone with the link will be able to view it again.",
				"Unlock page", JOptionPane.OK_CANCEL_OPTION);
			if (ok == JOptionPane.OK_OPTION)
			{
				plugin.actionSetLock("");
			}
			return;
		}
		JPasswordField field = new JPasswordField();
		int ok = JOptionPane.showConfirmDialog(this, field,
			"Set a passphrase — viewers of your page will need it", JOptionPane.OK_CANCEL_OPTION);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		String pw = new String(field.getPassword());
		if (pw.trim().isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Empty passphrase — nothing changed.");
			return;
		}
		plugin.actionSetLock(pw);
	}

	private void onDeleteClicked()
	{
		if (plugin.deletePendingTs() != null)
		{
			plugin.actionScheduleDelete(true);   // cancel
			return;
		}
		int ok = JOptionPane.showConfirmDialog(this,
			"Schedule deletion of your cloud profile and all its data?\n"
				+ "It is removed in 7 days. You can cancel any time before then.",
			"Delete my data", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (ok == JOptionPane.OK_OPTION)
		{
			plugin.actionScheduleDelete(false);
		}
	}

	/** Refresh labels + buttons from plugin state; safe to call from any thread. */
	void update()
	{
		SwingUtilities.invokeLater(() ->
		{
			// State 1 — journaling locally (the default). No server controls.
			if (!plugin.cloudActive())
			{
				String rsn = plugin.displayRsn();
				rsnLabel.setText(rsn != null && !rsn.isEmpty()
					? "<html><b>" + escape(rsn) + "</b> · journaling</html>"
					: "Chronicle");
				statusLabel.setText("<html>Journaling locally — everything stays on this "
					+ "computer, nothing is sent anywhere. Cloud sync lives under Advanced "
					+ "in the plugin settings, off and blank by default.</html>");
				pushNowButton.setVisible(false);
				reEnrolButton.setVisible(false);
				openPageButton.setVisible(false);
				privacySection.setVisible(false);
				revalidate();
				repaint();
				return;
			}

			// State 2 — cloud sync on. Full sync + privacy controls.
			pushNowButton.setVisible(true);
			reEnrolButton.setVisible(true);
			openPageButton.setText("Open my page");
			openPageButton.setVisible(true);
			privacySection.setVisible(true);

			String rsn = plugin.enrolledRsn();
			boolean enrolled = rsn != null && !rsn.isEmpty();
			rsnLabel.setText(enrolled
				? "<html>Enrolled: <b>" + escape(rsn) + "</b></html>"
				: "Not enrolled.");
			statusLabel.setText("<html>" + escape(plugin.statusLine()) + "</html>");
			openPageButton.setEnabled(enrolled);

			lockButton.setText(plugin.pageLocked() ? "Unlock page" : "Lock page (set passphrase)");
			listButton.setText(plugin.publicListed() ? "Unlist from directory" : "List in public directory");

			Long pending = plugin.deletePendingTs();
			deleteButton.setText(pending != null ? "Cancel deletion" : "Delete my data");

			String state;
			if (!enrolled)
			{
				state = "Enrol to manage privacy.";
			}
			else if (pending != null)
			{
				state = "<b>Deletion scheduled for " + WHEN.format(Instant.ofEpochSecond(pending))
					+ "</b> — cancel below to keep your data.";
			}
			else
			{
				state = "Page: " + (plugin.pageLocked() ? "locked" : "open by link")
					+ " · " + (plugin.publicListed() ? "listed publicly" : "unlisted");
			}
			privacyState.setText("<html>" + state + "</html>");

			lockButton.setEnabled(enrolled);
			listButton.setEnabled(enrolled);
			deleteButton.setEnabled(enrolled);

			revalidate();
			repaint();
		});
	}

	private static Component vgap(int h)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, h));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static String encode(String rsn)
	{
		// Site slug form: lowercase, spaces -> dashes (see server _player_key).
		return rsn.trim().toLowerCase().replace(' ', '-');
	}

	private static String escape(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
