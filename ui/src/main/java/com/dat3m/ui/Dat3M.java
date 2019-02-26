package com.dat3m.ui;

import javax.swing.*;
import javax.swing.border.TitledBorder;

import com.dat3m.dartagnan.program.Program;
import com.dat3m.dartagnan.program.utils.Alias;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.utils.Arch;
import com.dat3m.dartagnan.wmm.utils.Mode;
import com.dat3m.porthos.Porthos;
import com.dat3m.porthos.PorthosResult;
import com.microsoft.z3.Context;
import com.dat3m.ui.editor.MMEditor;
import com.dat3m.ui.editor.ProgramEditor;

import static com.dat3m.dartagnan.Dartagnan.testProgram;
import static com.dat3m.dartagnan.program.utils.Alias.CFIS;
import static com.dat3m.dartagnan.program.utils.Alias.CFS;
import static com.dat3m.dartagnan.wmm.utils.Arch.ARM;
import static com.dat3m.dartagnan.wmm.utils.Arch.ARM8;
import static com.dat3m.dartagnan.wmm.utils.Arch.POWER;
import static com.dat3m.dartagnan.wmm.utils.Arch.TSO;
import static com.dat3m.dartagnan.wmm.utils.Mode.IDL;
import static com.dat3m.dartagnan.wmm.utils.Mode.KLEENE;
import static com.dat3m.dartagnan.wmm.utils.Mode.KNASTER;
import static com.dat3m.ui.Task.PORTABILITY;
import static com.dat3m.ui.Task.REACHABILITY;
import static com.dat3m.ui.editor.EditorUtils.parseMMEditor;
import static com.dat3m.ui.editor.EditorUtils.parseProgramEditor;
import static java.awt.FlowLayout.LEFT;
import static javax.swing.BorderFactory.createCompoundBorder;
import static javax.swing.BorderFactory.createEmptyBorder;
import static javax.swing.BorderFactory.createTitledBorder;
import static javax.swing.JOptionPane.showMessageDialog;
import static javax.swing.SwingUtilities.invokeLater;
import static javax.swing.border.TitledBorder.CENTER;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;

public class Dat3M extends JPanel implements ActionListener {

	private static final String MMLABEL = "Memory Model";
	private static final String SMMLABEL = "Source Memory Model";
	private static final String TMMLABEL = "Target Memory Model";
	
    public static final ImageIcon dat3mIcon = new ImageIcon(Dat3M.class.getResource("/dat3m.png"), "Dat3m") {
	    @Override
	    public void paintIcon( Component c, Graphics g, int x, int y ) {
	        g.drawImage(getImage(), x, y, c.getWidth(), c.getHeight(), c);
	    }
	    
	    @Override 
	    public int getIconHeight() {
			return 60;
	    }

	    @Override
	    public int getIconWidth() {
			return 60;
	    }
	};

    protected static final int widht = getMainScreenWidth();
    protected static final int height = getMainScreenHeight();
	protected static JMenuBar menuBar = new JMenuBar();
	protected static JMenu menu = new JMenu("Import");

    protected static final ImageIcon dartagnanIcon = new ImageIcon(Dat3M.class.getResource("/dartagnan.jpg"), "Dartagnan");
    protected static final ImageIcon porthosIcon = new ImageIcon(Dat3M.class.getResource("/porthos.jpg"), "Porthos");
    protected JLabel iconPane = new JLabel(dartagnanIcon, JLabel.CENTER);

    private ProgramEditor programEditor;
	private MMEditor smmEditor;
	private MMEditor tmmEditor;
	private JTextPane consolePane;
	private JTextField boundField;
	
	protected Task task = Task.REACHABILITY;
	protected Arch target = Arch.NONE;
	protected Arch source = Arch.NONE;
	protected Mode mode = Mode.KNASTER;
	protected Alias alias = Alias.CFS;
	protected int bound = 1;

	private JSplitPane vSplitEditors;
	private JSplitPane archPane;
	private JPanel sArchPane;
	private JPanel tArchPane;
	private JFileChooser chooser = new JFileChooser();

	private ImporterMenuItem smmMenuIte;

	private ImporterMenuItem pMenuItem;

	private ImporterMenuItem tmmMenuIte;

    public Dat3M() {

        setLayout(new BorderLayout());

        programEditor = new ProgramEditor(menu);
        programEditor.setMinimumSize(new Dimension(widht / 3, 100));
        ArrayList<String> pExtensions = new ArrayList<String>();
        pExtensions.add("litmus");
        pExtensions.add("pts");
        pMenuItem = new ImporterMenuItem("Program", chooser, pExtensions, programEditor.getEditor());
        menu.add(pMenuItem);
        
        smmEditor = new MMEditor(menu, new JEditorPane(), SMMLABEL);
        smmEditor.setMinimumSize(new Dimension(100, height / 3));
        ArrayList<String> mmExtensions = new ArrayList<String>();
        mmExtensions.add("cat");
        smmMenuIte = new ImporterMenuItem(SMMLABEL, chooser, mmExtensions, smmEditor.getEditor());
        
        tmmEditor = new MMEditor(menu, new JEditorPane(), MMLABEL);
        tmmEditor.setMinimumSize(new Dimension(100, height / 3));
        tmmMenuIte = new ImporterMenuItem(MMLABEL, chooser, mmExtensions, tmmEditor.getEditor());
        menu.add(tmmMenuIte);
        
        Task[] tasks = { REACHABILITY, PORTABILITY };
		JPanel taskPane = createSelector(tasks, "Task");

		Arch[] archs = { Arch.NONE, TSO, POWER, ARM, ARM8 };
		tArchPane = createSelector(archs, "Target");
		sArchPane = createSelector(archs, "Source");
		archPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		archPane.setRightComponent(tArchPane);

        Mode[] modes = { KNASTER, IDL, KLEENE };
        JPanel modePane = createSelector(modes, "Mode");

        Alias[] aliases = { CFS, Alias.NONE, CFIS };
        JPanel aliasPane = createSelector(aliases, "Alias");

        // Bound editor
        boundField = new JTextField(3);
        boundField.setText("1");
        boundField.setActionCommand("Bound");
        boundField.addActionListener(this);
        boundField.addKeyListener(new BoundListener());
        JLabel uLabel = new JLabel("Unrolling Bound: ");
		JPanel boundPane = new JPanel(new FlowLayout(LEFT));
		boundPane.add(uLabel);
		boundPane.add(boundField);

        // Console.
        consolePane = new JTextPane();
        consolePane.setEditable(false);
        JScrollPane scrollConsole = new JScrollPane(consolePane);
        scrollConsole.setMinimumSize(new Dimension(0, 150));
        scrollConsole.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Test button.
        JButton testButton = new JButton("Test");
        testButton.setMaximumSize(new Dimension(widht, 50));
        testButton.addActionListener(this);

        // Clear button.
        JButton clearButton = new JButton("Clear");
        clearButton.setMaximumSize(new Dimension(widht, 50));
        clearButton.addActionListener(this);

        iconPane.setIcon(dartagnanIcon);

        //Put the options in a split pane.
        JSplitPane sp0 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, iconPane, taskPane);
        JSplitPane sp1 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp0, archPane);
        JSplitPane sp2 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp1, modePane);
        JSplitPane sp3 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp2, aliasPane);
        JSplitPane sp4 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp3, boundPane);
        JSplitPane sp5 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp4, testButton);
        JSplitPane sp6 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp5, clearButton);
        JSplitPane sp7 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp6, scrollConsole);

        JPanel optionsPane = new JPanel(new GridLayout(1,0));
        optionsPane.add(sp7);

        TitledBorder titledBorder = createTitledBorder("Options");
        titledBorder.setTitleJustification(CENTER);
        optionsPane.setBorder(titledBorder);
        optionsPane.setMaximumSize(new Dimension(dartagnanIcon.getIconWidth(), 100));

        //Put the editors in a split pane.
        vSplitEditors = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        vSplitEditors.setBottomComponent(tmmEditor);
        vSplitEditors.setOneTouchExpandable(true);
        vSplitEditors.setDividerSize(2);
        vSplitEditors.setDividerLocation(0.5);
        vSplitEditors.setMinimumSize(new Dimension(widht / 3, 100));
        
        JSplitPane hSplitEditors = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, programEditor, vSplitEditors);
        hSplitEditors.setOneTouchExpandable(true);
        hSplitEditors.setDividerLocation(0.4);
        hSplitEditors.setDividerSize(2);
        
        JSplitPane mainPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, optionsPane, hSplitEditors);
        add(mainPane);
    }

    private void updateGUIonTask() {
    	TitledBorder titledBorder = createTitledBorder("");
        titledBorder.setTitleJustification(CENTER);
    	switch(task){
        case REACHABILITY:
        	// Update image
        	iconPane.setIcon(dartagnanIcon);
        	// Remove smmEditor
        	if(vSplitEditors.getTopComponent() == smmEditor) {
                vSplitEditors.remove(smmEditor);        		
        	}
        	// Update editor and menutItem labels
        	titledBorder.setTitle(MMLABEL);
            menu.getItem(1).setText(MMLABEL);
        	// Remove source selection option
        	if(archPane.getLeftComponent() == sArchPane) {
        		archPane.remove(sArchPane);        		
        	}
        	// Remove source memory model importer item
        	if(menu.getItemCount() > 2 && menu.getItem(2) == smmMenuIte) {
        		menu.remove(smmMenuIte);        		
        	}
            break;
        case PORTABILITY:
        	// Update image
        	iconPane.setIcon(porthosIcon);
        	// Add smmEditor
            vSplitEditors.setTopComponent(smmEditor);
        	// Update editor and menutItem labels
            titledBorder.setTitle(TMMLABEL);
            menu.getItem(1).setText(TMMLABEL);
        	// Add source selection option
            archPane.setLeftComponent(tArchPane);
        	// Add source memory model importer item
    		menu.add(smmMenuIte);
            break;
        }
    	consolePane.setText("");
    	tmmEditor.setBorder(createCompoundBorder(titledBorder, createEmptyBorder(5,5,5,5)));
    }

	private JPanel createSelector(Object[] options, String label) {
		JComboBox<?> selector = new JComboBox<Object>(options);
    	selector.setActionCommand(label);
        selector.addActionListener(this);
        JLabel sLabel = new JLabel(label + ": ");
		JPanel pane = new JPanel(new FlowLayout(LEFT));
        pane.add(sLabel);
        pane.add(selector);
		return pane;
	}

    /**
     * Create the GUI and show it.  For thread safety,
     * this method should be invoked from the
     * event dispatch thread.
     */
	private static void createAndShowGUI() {
        //Create and set up the window.
        JFrame frame = new JFrame("Dat3M");
		frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		menuBar.add(menu);
		frame.setJMenuBar(menuBar);
		frame.setIconImage(dat3mIcon.getImage());

        //Add content to the window.
        frame.add(new Dat3M());
        
        //Display the window.
        frame.pack();
        frame.setVisible(true);
    }

    public static void main(String[] args) {
		UIManager.getDefaults().put("SplitPane.border", BorderFactory.createEmptyBorder());
        invokeLater(new Runnable() {public void run() {createAndShowGUI();}});
    }

	@SuppressWarnings("unchecked")
	@Override
	public void actionPerformed(ActionEvent e) {
		
		if(boundField.getText().equals("")) {
			boundField.setText("1");
		}

		if(e.getActionCommand().equals("Task")) {
			Object source = e.getSource();
			if(source instanceof JComboBox<?>) {
				task = (Task) ((JComboBox<Task>)source).getSelectedItem();
				updateGUIonTask();
			}
		}

		if(e.getActionCommand().equals("Target")) {
			Object source = e.getSource();
			if(source instanceof JComboBox<?>) {
				target = (Arch) ((JComboBox<Arch>)source).getSelectedItem();
			}
		}

		if(e.getActionCommand().equals("Source")) {
			Object source = e.getSource();
			if(source instanceof JComboBox<?>) {
				source = (Arch) ((JComboBox<Arch>)source).getSelectedItem();
			}
		}

		if(e.getActionCommand().equals("Mode")) {
			Object source = e.getSource();
			if(source instanceof JComboBox<?>) {
				mode = (Mode) ((JComboBox<Mode>)source).getSelectedItem();
			}
		}

		if(e.getActionCommand().equals("Alias")) {
			Object source = e.getSource();
			if(source instanceof JComboBox<?>) {
				alias = (Alias) ((JComboBox<Alias>)source).getSelectedItem();
			}
		}

		if(e.getActionCommand().equals("Test")) {
	        Program pSource = null;
	        Program pTarget = null;
			try {
				pSource = parseProgramEditor(programEditor.getEditor(), "pts");
				pTarget = parseProgramEditor(programEditor.getEditor(), "pts");
			} catch (Exception exp) {
				showMessageDialog(null, "The program was not imported or cannot be parsed", "About", JOptionPane.INFORMATION_MESSAGE, dat3mIcon);
				return;
			}
			
			Wmm smm = null;
			Wmm tmm = null;
			try {				
				tmm = parseMMEditor(tmmEditor.getEditor(), target);
			} catch (Exception exp) {
				String dummy =  task == REACHABILITY ? " " : " target ";
				showMessageDialog(null, "The" +  dummy + "memory model was not imported or cannot be parsed", "About", JOptionPane.INFORMATION_MESSAGE, dat3mIcon);	
				return;
			}
			if(task == Task.PORTABILITY) {
				try {				
					smm = parseMMEditor(smmEditor.getEditor(), source);
				} catch (Exception exp) {
					showMessageDialog(null, "The source memory model was not imported or cannot be parsed", "About", JOptionPane.INFORMATION_MESSAGE, dat3mIcon);	
					return;
				}				
			}
			
			Context ctx = new Context();
			String result = "";
			
	    	switch(task){
	        case REACHABILITY:
	    		result = "Condition " + pSource.getAss().toStringWithType() + "\n";
	    		result += testProgram(ctx.mkSolver(), ctx, pSource, tmm, target, bound, mode, alias) ? "OK" : "No";	    		
				break;
	        case PORTABILITY:
	    		PorthosResult res = Porthos.testProgram(ctx.mkSolver(), ctx.mkSolver(), ctx, pSource, pTarget, source, target,
	                    smm, tmm, bound, mode, alias);
	    		String dummy = res.getIsPortable()? " " : " not ";
	    		result = "The program is" + dummy + "state-portable \nIterations: " + res.getIterations();
	            break;
	        }
	    	consolePane.setText(result);
		}

		if(e.getActionCommand().equals("Clear")) {
			consolePane.setText("");
		}
	}
	
	class BoundListener implements KeyListener {
		@Override
		public void keyTyped(KeyEvent event) {
			try {
				bound = Integer.parseInt(boundField.getText());				
			} catch (Exception e) {
				// Nothing to do here
			}
		}

		@Override
		public void keyPressed(KeyEvent arg0) {
			// Nothing to do here
		}

		@Override
		public void keyReleased(KeyEvent arg0) {
			// Nothing to do here				
		}
	};
	
	public static int getMainScreenWidth() {
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
	    GraphicsDevice[] gs = ge.getScreenDevices();
	    if (gs.length > 0) {
	        return (int) Math.round(gs[0].getDisplayMode().getWidth());
	    }
	    return 0;
	}
	
	public static int getMainScreenHeight() {
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
	    GraphicsDevice[] gs = ge.getScreenDevices();
	    if (gs.length > 0) {
	        return (int) Math.round(gs[0].getDisplayMode().getHeight());
	    }
	    return 0;
	}
}
