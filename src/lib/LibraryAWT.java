package lib;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.Collectors;



public class LibraryAWT extends JFrame {
    // ---------------- Data models ----------------
    private static final String DATA_FILE_NAME = "library_data.ser";

    static class Book implements Serializable {
        String id, title, author;
        int year, total, available;
        Book(String id, String t, String a, int y, int tot) { this.id = id; this.title = t; this.author = a; this.year = y; this.total = tot; this.available = tot; }
    }

    static class Member implements Serializable {
        String id, name;
        Member(String id, String n) { this.id = id; this.name = n; }
    }

    static class Loan implements Serializable {
        String loanId, bookId, memberId;
        long issueDate; // new: store epoch ms when loan was created

        Loan(String L, String b, String m) {
            this.loanId = L;
            this.bookId = b;
            this.memberId = m;
            this.issueDate = System.currentTimeMillis();
        }

        // helper constructor to set custom issueDate (useful for seeding/testing)
        Loan(String L, String b, String m, long issueDate) {
            this.loanId = L;
            this.bookId = b;
            this.memberId = m;
            this.issueDate = issueDate;
        }
    }

    List<Book> books = new ArrayList<>();
    List<Member> members = new ArrayList<>();
    List<Loan> loans = new ArrayList<>();

    // ---------------- UI shared ----------------
    private final Color CARD = new Color(255,255,255);
    private final Color ACCENT = new Color(232,106,87);
    private final Color ACCENT_SOFT = new Color(245,150,128);
    private final Color FG = new Color(40,40,45);
    private final Color MUTED = new Color(120,120,125);
    private final int RADIUS = 14;

    CardLayout cardLayout = new CardLayout();
    JPanel mainPanel = new JPanel(cardLayout);

    // transient UI controls (recreated at runtime)
    private transient JTextField bookSearch, memberSearch, loanSearch;
    private transient DefaultListModel<String> bookSugModel, memberSugModel, loanSugModel;
    private transient JList<String> bookSugList, memberSugList, loanSugList;
    private transient JPopupMenu bookPopup, memberPopup, loanPopup;
    private transient JPanel bookListPanel, memberListPanel, loanListPanel;

    public LibraryAWT(){
        super("Library Management System");
        setSize(1100,700); setLocationRelativeTo(null); setDefaultCloseOperation(EXIT_ON_CLOSE);

        // models used for compatibility (not shown) still kept
        initTransientUI();       // create fields & components
        buildPages();            // add pages to mainPanel
        add(mainPanel);

        if (!loadData()) { seedSampleData(); saveData(); } // load or seed+save

        refreshAll();
        cardLayout.show(mainPanel, "Home");
        setVisible(true);
    }

    // ---------------- Initialization helpers ----------------
    private void initTransientUI(){
        bookSearch = new JTextField(); memberSearch = new JTextField(); loanSearch = new JTextField();
        bookListPanel = createListContainer(); memberListPanel = createListContainer(); loanListPanel = createListContainer();

        bookSugModel = new DefaultListModel<>(); memberSugModel = new DefaultListModel<>(); loanSugModel = new DefaultListModel<>();
        bookSugList = new JList<>(bookSugModel); memberSugList = new JList<>(memberSugModel); loanSugList = new JList<>(loanSugModel);

        bookPopup = new JPopupMenu(); memberPopup = new JPopupMenu(); loanPopup = new JPopupMenu();
        addPopupScroll(bookPopup, bookSugList); addPopupScroll(memberPopup, memberSugList); addPopupScroll(loanPopup, loanSugList);

        // wire suggestion behavior (title/author/id patterns reused)
        makeSuggestion(bookSearch, bookSugModel, bookSugList, bookPopup, q -> {
            LinkedHashSet<String> s = new LinkedHashSet<>();
            for (Book b: books) {
                if (contains(b.title,q)) s.add(b.title);
                if (contains(b.author,q)) s.add(b.author);
                if (contains(b.id,q)) s.add(b.id);
            }
            return s;
        }, sel -> { bookSearch.setText(sel); refreshBookList(sel); });

        makeSuggestion(memberSearch, memberSugModel, memberSugList, memberPopup, q -> {
            LinkedHashSet<String> s = new LinkedHashSet<>();
            for (Member m: members) { if (contains(m.name,q)) s.add(m.name); if (contains(m.id,q)) s.add(m.id); }
            return s;
        }, sel -> { memberSearch.setText(sel); refreshMemberList(sel); });

        makeSuggestion(loanSearch, loanSugModel, loanSugList, loanPopup, q -> {
            LinkedHashSet<String> s = new LinkedHashSet<>();
            for (Loan l: loans) { if (contains(l.loanId,q)) s.add(l.loanId); if (contains(l.bookId,q)) s.add(l.bookId); if (contains(l.memberId,q)) s.add(l.memberId); }
            return s;
        }, sel -> { loanSearch.setText(sel); refreshLoanList(sel); });
    }

    private void addPopupScroll(JPopupMenu popup, JList<String> list){
        JScrollPane sp = new JScrollPane(list); sp.setBorder(null); sp.setPreferredSize(new Dimension(300,140)); popup.add(sp); popup.setFocusable(false);
    }

    private boolean contains(String s, String q){ return s!=null && q!=null && s.toLowerCase().contains(q); }

    // create suggestion wiring: document listener + click + keys
    private void makeSuggestion(JTextField field, DefaultListModel<String> model, JList<String> list, JPopupMenu popup,
                                Function<String,LinkedHashSet<String>> source, Consumer<String> onSelect){
        field.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){ update(); } public void removeUpdate(DocumentEvent e){ update(); } public void changedUpdate(DocumentEvent e){ update(); }
            private void update(){
                SwingUtilities.invokeLater(() -> {
                    String q = field.getText().trim().toLowerCase();
                    model.clear();
                    if(q.isEmpty()){ popup.setVisible(false); return; }
                    int c=0;
                    for(String s: source.apply(q)){ model.addElement(s); if(++c>=20) break; }
                    if(model.isEmpty()) popup.setVisible(false); else { list.setSelectedIndex(0); if(!popup.isVisible()) popup.show(field,0,field.getHeight()); }
                });
            }
        });

        list.addMouseListener(new MouseAdapter(){ @Override public void mouseClicked(MouseEvent e){ if(list.getSelectedValue()!=null){ onSelect.accept(list.getSelectedValue()); popup.setVisible(false);} }});

        field.addKeyListener(new KeyAdapter(){ @Override public void keyPressed(KeyEvent e){
            if(e.getKeyCode()==KeyEvent.VK_ENTER){ String q=field.getText().trim(); popup.setVisible(false); if(q.isEmpty()) { refreshAll(); } else { onSelect.accept(q); } }
            else if(e.getKeyCode()==KeyEvent.VK_ESCAPE) popup.setVisible(false);
        }});
    }

    // ---------------- Build pages ----------------
    private void buildPages(){
        mainPanel.add(createHomePage(),"Home");
        mainPanel.add(createBooksPage(),"Books");
        mainPanel.add(createMembersPage(),"Members");
        mainPanel.add(createLoansPage(),"Loans");
    }

    private JPanel gradientPanel(){
        return new JPanel(){ protected void paintComponent(Graphics g){ super.paintComponent(g); Graphics2D g2=(Graphics2D)g.create(); int w=getWidth(), h=getHeight(); GradientPaint gp=new GradientPaint(0,0,new Color(255,246,239), w,h,new Color(250,212,192)); g2.setPaint(gp); g2.fillRect(0,0,w,h); g2.dispose(); } };
    }

    private JPanel createHomePage(){
        JPanel page = gradientPanel(); page.setLayout(new BorderLayout()); page.setBorder(new EmptyBorder(20,20,20,20));
        JLabel title = new JLabel("<html><center><span style='font-size:32px;color:#28303a'>📚 Library Management System</span></center></html>", JLabel.CENTER);
        title.setBorder(new EmptyBorder(20,10,30,10)); page.add(title, BorderLayout.NORTH);
        JPanel center = new JPanel(new GridBagLayout()); center.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints(); gc.insets = new Insets(16,16,16,16);
        JButton bbtn = homeIconButton("Books","📖"), mbtn = homeIconButton("Members","👥"), lbtn = homeIconButton("Loans","💳");
        bbtn.addActionListener(e->cardLayout.show(mainPanel,"Books")); mbtn.addActionListener(e->cardLayout.show(mainPanel,"Members")); lbtn.addActionListener(e->cardLayout.show(mainPanel,"Loans"));
        gc.gridx=0; center.add(bbtn,gc); gc.gridx=1; center.add(mbtn,gc); gc.gridx=2; center.add(lbtn,gc);
        page.add(center, BorderLayout.CENTER); return page;
    }

    private JPanel createBooksPage(){
        JPanel page = gradientPanel(); page.setLayout(new BorderLayout()); page.setBorder(new EmptyBorder(14,14,14,14));
        JLabel title = new JLabel("📖 Books"); title.setForeground(FG); title.setFont(new Font("Serif",Font.BOLD,26)); page.add(title, BorderLayout.NORTH);

        JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false);
        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT,10,10)); leftActions.setOpaque(false);
        JButton addBook = actionButton("➕ Add Book"), home = actionButton("⬅ Home"); addBook.addActionListener(e->addBookDialog()); home.addActionListener(e->cardLayout.show(mainPanel,"Home"));
        leftActions.add(addBook); leftActions.add(home); top.add(leftActions, BorderLayout.WEST);

        JPanel searchWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,8)); searchWrap.setOpaque(false);
        JLabel sLabel = new JLabel("Search:"); sLabel.setForeground(MUTED); searchWrap.add(sLabel); bookSearch.setPreferredSize(new Dimension(280,28)); searchWrap.add(bookSearch);
        top.add(searchWrap, BorderLayout.EAST);
        page.add(top, BorderLayout.PAGE_START);

        JScrollPane scroll = new JScrollPane(bookListPanel); scroll.setOpaque(false); scroll.getViewport().setOpaque(false); scroll.setBorder(null);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private JPanel createMembersPage(){
        JPanel page = gradientPanel(); page.setLayout(new BorderLayout()); page.setBorder(new EmptyBorder(14,14,14,14));
        JLabel title = new JLabel("👥 Members"); title.setForeground(FG); title.setFont(new Font("Serif",Font.BOLD,26)); page.add(title, BorderLayout.NORTH);

        JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT,10,10)); left.setOpaque(false);
        JButton addMember = actionButton("➕ Add Member"), home = actionButton("⬅ Home"); addMember.addActionListener(e->addMemberDialog()); home.addActionListener(e->cardLayout.show(mainPanel,"Home"));
        left.add(addMember); left.add(home); top.add(left, BorderLayout.WEST);

        JPanel searchWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,8)); searchWrap.setOpaque(false);
        JLabel sLabel = new JLabel("Search:"); sLabel.setForeground(MUTED); searchWrap.add(sLabel); memberSearch.setPreferredSize(new Dimension(280,28)); searchWrap.add(memberSearch);
        top.add(searchWrap, BorderLayout.EAST);
        page.add(top, BorderLayout.PAGE_START);

        JScrollPane scroll = new JScrollPane(memberListPanel); scroll.setOpaque(false); scroll.getViewport().setOpaque(false); scroll.setBorder(null);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private JPanel createLoansPage(){
        JPanel page = gradientPanel(); page.setLayout(new BorderLayout()); page.setBorder(new EmptyBorder(14,14,14,14));
        JLabel title = new JLabel("💳 Loans"); title.setForeground(FG); title.setFont(new Font("Serif",Font.BOLD,26)); page.add(title, BorderLayout.NORTH);

        JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT,10,10)); left.setOpaque(false);
        JButton borrow = actionButton("📥 Borrow Book"), ret = actionButton("📤 Return Book"), home = actionButton("⬅ Home");
        borrow.addActionListener(e->borrowDialog()); ret.addActionListener(e->returnDialog()); home.addActionListener(e->cardLayout.show(mainPanel,"Home"));
        left.add(borrow); left.add(ret); left.add(home); top.add(left, BorderLayout.WEST);

        JPanel searchWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,8)); searchWrap.setOpaque(false);
        JLabel sLabel = new JLabel("Search:"); sLabel.setForeground(MUTED); searchWrap.add(sLabel); loanSearch.setPreferredSize(new Dimension(280,28)); searchWrap.add(loanSearch);
        top.add(searchWrap, BorderLayout.EAST);

        page.add(top, BorderLayout.PAGE_START);
        JScrollPane scroll = new JScrollPane(loanListPanel); scroll.setOpaque(false); scroll.getViewport().setOpaque(false); scroll.setBorder(null);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private JPanel createListContainer(){ JPanel p=new JPanel(); p.setOpaque(false); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); return p; }

    // ---------------- UI element factories ----------------
    private JButton homeIconButton(String label, String emoji){
        final boolean[] hover = {false};
        JButton b = new JButton(){ protected void paintComponent(Graphics g){
            Graphics2D g2=(Graphics2D)g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(), h=getHeight(); GradientPaint gp=new GradientPaint(0,0,new Color(255,248,240), w,h,new Color(245,210,195));
            g2.setPaint(gp); g2.fillRoundRect(0,0,w,h,24,24);
            if(hover[0]){ g2.setColor(new Color(0,0,0,50)); g2.fillRoundRect(3,3,w-6,h-6,24,24); }
            super.paintComponent(g2); g2.dispose();
        }};
        b.setContentAreaFilled(false); b.setOpaque(false); JLabel lbl = new JLabel("<html><center><span style='font-size:32px;'>"+emoji+"</span><br><span style='font-size:15px;color:#5A4F48'>"+label+"</span></center></html>", JLabel.CENTER);
        b.setLayout(new BorderLayout()); b.add(lbl,BorderLayout.CENTER); b.setPreferredSize(new Dimension(200,130)); b.setBorder(new EmptyBorder(10,10,10,10)); b.setFocusPainted(false);
        b.addMouseListener(new MouseAdapter(){ public void mouseEntered(MouseEvent e){ hover[0]=true; b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); b.repaint(); } public void mouseExited(MouseEvent e){ hover[0]=false; b.setCursor(Cursor.getDefaultCursor()); b.repaint(); }});
        return b;
    }

    private JButton actionButton(String text){ JButton b=new JButton(text); b.setFont(new Font("SansSerif",Font.PLAIN,14)); b.setForeground(FG); b.setBackground(new Color(255,245,236)); b.setFocusPainted(false); b.setBorder(new CompoundBorder(new LineBorder(new Color(220,210,205),1,true), new EmptyBorder(8,12,8,12))); return b; }

    private JPanel createCard(){ JPanel card = new JPanel(){ protected void paintComponent(Graphics g){ super.paintComponent(g); Graphics2D g2=(Graphics2D)g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); int w=getWidth(), h=getHeight(); g2.setColor(new Color(0,0,0,30)); g2.fillRoundRect(5,7,w-10,h-10,RADIUS,RADIUS); g2.setColor(CARD); g2.fillRoundRect(0,0,w-10,h-10,RADIUS,RADIUS); g2.dispose(); } }; card.setOpaque(false); card.setLayout(new BorderLayout(10,10)); card.setBorder(new EmptyBorder(10,10,10,10)); return card; }

    private JPanel badgePanel(String letter){ JPanel p=new JPanel(new GridBagLayout()); p.setOpaque(false); p.setBorder(new EmptyBorder(6,12,6,6)); p.add(new JLabel(new CircleIcon(letter,42,ACCENT,Color.white))); return p; }

    // Circle icon (same behavior)
    private static class CircleIcon implements Icon { private final String letter; private final int size; private final Color bg; private final Color fg; CircleIcon(String letter,int size,Color bg,Color fg){this.letter=letter;this.size=size;this.bg=bg;this.fg=fg;} public void paintIcon(Component c, Graphics g, int x, int y){ Graphics2D g2=(Graphics2D)g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g2.setColor(bg); g2.fillOval(x,y,size,size); g2.setColor(new Color(0,0,0,20)); g2.drawOval(x+1,y+1,size-2,size-2); g2.setColor(fg); g2.setFont(new Font("SansSerif",Font.BOLD,size/2)); FontMetrics fm=g2.getFontMetrics(); int tx=x+(size-fm.stringWidth(letter))/2; int ty=y+(size-fm.getHeight())/2+fm.getAscent(); g2.drawString(letter,tx,ty); g2.dispose(); } public int getIconWidth(){return size;} public int getIconHeight(){return size;} }

    // ---------------- List refreshers (templated by type) ----------------
    private void refreshAll(){ refreshBookList(null); refreshMemberList(null); refreshLoanList(null); }

    private void refreshBookList(String query){
        bookListPanel.removeAll();
        List<Book> toShow = (query==null||query.trim().isEmpty())? new ArrayList<>(books)
                : books.stream().filter(b -> contains(b.title,query.toLowerCase())||contains(b.author,query.toLowerCase())||contains(b.id,query.toLowerCase())).collect(Collectors.toList());
        if(toShow.isEmpty()) bookListPanel.add(emptyLabel("No books found."));
        else { int i=1; for(Book b: toShow){ JPanel card = createCard(); card.setMaximumSize(new Dimension(Integer.MAX_VALUE,110)); card.add(badgePanel(indexToLetters(i)), BorderLayout.WEST);
            JPanel center = new JPanel(); center.setOpaque(false); center.setLayout(new BoxLayout(center,BoxLayout.Y_AXIS));
            JLabel t = new JLabel(b.title==null||b.title.isEmpty()?"Untitled":b.title); t.setForeground(FG); t.setFont(new Font("Serif",Font.BOLD,18));
            JLabel a = new JLabel("by " + (b.author==null||b.author.isEmpty()?"Unknown":b.author)); a.setForeground(MUTED); a.setFont(new Font("SansSerif",Font.ITALIC,12));
            JLabel d = new JLabel("Year: "+b.year+"   Available: "+b.available+"/"+b.total); d.setForeground(MUTED); d.setFont(new Font("SansSerif",Font.PLAIN,12));
            center.add(t); center.add(Box.createVerticalStrut(6)); center.add(a); center.add(Box.createVerticalStrut(6)); center.add(d);
            card.add(center, BorderLayout.CENTER);
            JPanel right = new JPanel(new BorderLayout()); right.setOpaque(false); JLabel idL = new JLabel("ID: "+b.id, JLabel.RIGHT); idL.setForeground(ACCENT); idL.setFont(new Font("Monospaced",Font.BOLD,13));
            JLabel hint = new JLabel("<html><i style='color:#A07B73'>Click for details</i></html>", JLabel.RIGHT); hint.setFont(new Font("SansSerif", Font.PLAIN, 11));
            right.add(idL, BorderLayout.NORTH); right.add(hint, BorderLayout.SOUTH); right.setBorder(new EmptyBorder(6,6,6,12)); card.add(right, BorderLayout.EAST);
            addCardHover(card, ()-> showBookDetail(b)); bookListPanel.add(card); bookListPanel.add(Box.createVerticalStrut(12)); i++; } }
        bookListPanel.revalidate(); bookListPanel.repaint();
    }

    private void refreshMemberList(String query){
        memberListPanel.removeAll();
        List<Member> toShow = (query==null||query.trim().isEmpty())? new ArrayList<>(members)
                : members.stream().filter(m -> contains(m.name,query.toLowerCase())||contains(m.id,query.toLowerCase())).collect(Collectors.toList());
        if(toShow.isEmpty()) memberListPanel.add(emptyLabel("No members found."));
        else { int i=1; for(Member m: toShow){ JPanel card=createCard(); card.setMaximumSize(new Dimension(Integer.MAX_VALUE,100));
            card.add(badgePanel(indexToLetters(i)), BorderLayout.WEST);
            JPanel center=new JPanel(); center.setOpaque(false); center.setLayout(new BoxLayout(center,BoxLayout.Y_AXIS));
            JLabel idBig=new JLabel(m.id); idBig.setForeground(ACCENT_SOFT); idBig.setFont(new Font("Monospaced",Font.BOLD,20));
            JLabel name=new JLabel(m.name); name.setForeground(FG); name.setFont(new Font("Serif",Font.BOLD,18));
            center.add(idBig); center.add(Box.createVerticalStrut(6)); center.add(name); card.add(center, BorderLayout.CENTER);
            JPanel right=new JPanel(new BorderLayout()); right.setOpaque(false); JLabel meta=new JLabel("Member", JLabel.RIGHT); meta.setForeground(MUTED); meta.setFont(new Font("SansSerif",Font.PLAIN,12));
            JLabel hint=new JLabel("<html><i style='color:#A07B73'>Click to view</i></html>", JLabel.RIGHT); hint.setFont(new Font("SansSerif",Font.PLAIN,11));
            right.add(meta, BorderLayout.NORTH); right.add(hint, BorderLayout.SOUTH); right.setBorder(new EmptyBorder(6,6,6,12)); card.add(right, BorderLayout.EAST);
            addCardHover(card, ()-> showMemberDetail(m)); memberListPanel.add(card); memberListPanel.add(Box.createVerticalStrut(12)); i++; } }
        memberListPanel.revalidate(); memberListPanel.repaint();
    }

    private void refreshLoanList(String query){
        loanListPanel.removeAll();
        List<Loan> toShow = (query==null||query.trim().isEmpty())? new ArrayList<>(loans)
                : loans.stream().filter(l -> contains(l.loanId,query.toLowerCase())||contains(l.bookId,query.toLowerCase())||contains(l.memberId,query.toLowerCase())).collect(Collectors.toList());
        if(toShow.isEmpty()) loanListPanel.add(emptyLabel("No loans found."));
        else { int i=1; for(Loan l: toShow){ JPanel card=createCard(); card.setMaximumSize(new Dimension(Integer.MAX_VALUE,130));
            card.add(badgePanel(indexToLetters(i)), BorderLayout.WEST);

            JPanel center=new JPanel(); center.setOpaque(false); center.setLayout(new BoxLayout(center,BoxLayout.Y_AXIS));

            JLabel loanId=new JLabel("Loan: "+ shortId(l.loanId)); loanId.setForeground(ACCENT_SOFT); loanId.setFont(new Font("Monospaced",Font.BOLD,16));
            JLabel details=new JLabel("Book → "+l.bookId+"       Member → "+l.memberId); details.setForeground(FG); details.setFont(new Font("SansSerif",Font.BOLD,14));

            // NEW UI LABELS: days, days left / overdue, fine
            int days = daysSince(l.issueDate);
            int fine = fineForLoan(l);
            int left = daysLeft(l);

            JLabel daysInfo = new JLabel("Issued: "+days+" days ago");
            daysInfo.setFont(new Font("SansSerif", Font.PLAIN, 12));
            daysInfo.setForeground(MUTED);

            JLabel leftInfo = new JLabel();
            leftInfo.setFont(new Font("SansSerif", Font.BOLD, 12));
            if (left < 0) {
                leftInfo.setText("Overdue by " + (-left) + " days");
                leftInfo.setForeground(Color.RED);
            } else {
                leftInfo.setText("Days Left: " + left);
                // warn when close to due
                leftInfo.setForeground(left <= 5 ? Color.RED : new Color(0,120,0));
            }

            JLabel fineInfo = new JLabel("Fine: ₹" + fine);
            fineInfo.setFont(new Font("SansSerif", Font.PLAIN, 12));
            fineInfo.setForeground(fine > 0 ? Color.RED : MUTED);

            center.add(loanId); center.add(Box.createVerticalStrut(6));
            center.add(details); center.add(Box.createVerticalStrut(6));
            center.add(daysInfo); center.add(Box.createVerticalStrut(4));
            center.add(leftInfo); center.add(Box.createVerticalStrut(4));
            center.add(fineInfo);

            card.add(center, BorderLayout.CENTER);

            JPanel right=new JPanel(new BorderLayout()); right.setOpaque(false); JLabel meta=new JLabel("Loan Record", JLabel.RIGHT); meta.setForeground(MUTED); meta.setFont(new Font("SansSerif",Font.PLAIN,12));
            JLabel hint=new JLabel("<html><i style='color:#A07B73'>Click for details</i></html>", JLabel.RIGHT); hint.setFont(new Font("SansSerif",Font.PLAIN,11));
            right.add(meta, BorderLayout.NORTH); right.add(hint, BorderLayout.SOUTH); right.setBorder(new EmptyBorder(6,6,6,12)); card.add(right, BorderLayout.EAST);

            addCardHover(card, ()-> showLoanDetail(l)); loanListPanel.add(card); loanListPanel.add(Box.createVerticalStrut(12)); i++; } }
        loanListPanel.revalidate(); loanListPanel.repaint();
    }

    private Component emptyLabel(String msg){ JLabel l=new JLabel(msg,JLabel.CENTER); l.setFont(new Font("SansSerif",Font.ITALIC,14)); l.setForeground(MUTED); l.setBorder(new EmptyBorder(20,10,20,10)); return l; }

    // ---------------- interactions ----------------
    private void addCardHover(JPanel card, Runnable onClick){
        card.addMouseListener(new MouseAdapter(){
            @Override public void mouseEntered(MouseEvent e){ card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); card.setBorder(new CompoundBorder(new LineBorder(ACCENT_SOFT,2,true), new EmptyBorder(10,10,10,10))); card.repaint(); }
            @Override public void mouseExited(MouseEvent e){ card.setCursor(Cursor.getDefaultCursor()); card.setBorder(new EmptyBorder(10,10,10,10)); card.repaint(); }
            @Override public void mouseClicked(MouseEvent e){ onClick.run(); }
        });
    }

    // ---------------- dialogs ----------------
    private void addBookDialog(){
        JTextField idF=new JTextField(), titleF=new JTextField(), authorF=new JTextField(), yearF=new JTextField(String.valueOf(Calendar.getInstance().get(Calendar.YEAR))), totalF=new JTextField("1");
        JPanel p=new JPanel(new GridLayout(0,1,6,6)); p.setBackground(new Color(255,245,236));
        p.add(labeledField("Book ID", idF)); p.add(labeledField("Title", titleF)); p.add(labeledField("Author", authorF)); p.add(labeledField("Year", yearF)); p.add(labeledField("Total Copies", totalF));
        if(JOptionPane.showConfirmDialog(this,p,"Add Book",JOptionPane.OK_CANCEL_OPTION)==0){
            String id=idF.getText().trim(), t=titleF.getText().trim(), a=authorF.getText().trim(); int y=parseIntOrDefault(yearF.getText(), Calendar.getInstance().get(Calendar.YEAR)); int tot=Math.max(1, parseIntOrDefault(totalF.getText(),1));
            if(!id.isEmpty()){ books.add(new Book(id,t,a,y,tot)); updateAfterChange(); }
        }
    }

    private void addMemberDialog(){
        JTextField idF=new JTextField(), nameF=new JTextField(); JPanel p=new JPanel(new GridLayout(0,1,6,6)); p.setBackground(new Color(255,245,236));
        p.add(labeledField("Member ID", idF)); p.add(labeledField("Name", nameF));
        if(JOptionPane.showConfirmDialog(this,p,"Add Member",JOptionPane.OK_CANCEL_OPTION)==0){
            String id=idF.getText().trim(), nm=nameF.getText().trim(); if(!id.isEmpty() && !nm.isEmpty()){ members.add(new Member(id,nm)); updateAfterChange(); refreshMemberList(null); }
        }
    }

    private void borrowDialog(){
        JTextField bookF=new JTextField(), memberF=new JTextField(); JPanel p=new JPanel(new GridLayout(0,1,6,6)); p.setBackground(new Color(255,245,236));
        p.add(labeledField("Book ID", bookF)); p.add(labeledField("Member ID", memberF));
        if(JOptionPane.showConfirmDialog(this,p,"Borrow Book",JOptionPane.OK_CANCEL_OPTION)==0){
            Book b=findBook(bookF.getText().trim()); Member m=findMember(memberF.getText().trim());
            if(b!=null && m!=null && b.available>0){
                b.available--;
                loans.add(new Loan(UUID.randomUUID().toString(), b.id, m.id)); // constructor sets issueDate to now
                updateAfterChange();
                refreshLoanList(null);
            } else JOptionPane.showMessageDialog(this,"Invalid or unavailable book/member.");
        }
    }

    private void returnDialog(){
        JTextField loanF=new JTextField(); JPanel p=new JPanel(new GridLayout(0,1,6,6)); p.setBackground(new Color(255,245,236)); p.add(labeledField("Loan ID", loanF));
        if(JOptionPane.showConfirmDialog(this,p,"Return Book",JOptionPane.OK_CANCEL_OPTION)==0){
            Loan L=findLoan(loanF.getText().trim()); if(L!=null){ Book b=findBook(L.bookId); if(b!=null) b.available++; loans.remove(L); updateAfterChange(); refreshLoanList(null); } else JOptionPane.showMessageDialog(this,"Loan not found.");
        }
    }

    private JPanel labeledField(String name, JTextField f){ JPanel p=new JPanel(new BorderLayout(6,6)); p.setOpaque(false); JLabel l=new JLabel(name); l.setForeground(FG); l.setFont(new Font("SansSerif",Font.PLAIN,12)); p.add(l,BorderLayout.WEST); p.add(f,BorderLayout.CENTER); return p; }

    // ---------------- details ----------------
    private void showBookDetail(Book b){ JOptionPane.showMessageDialog(this,new JLabel("<html><b>"+escape(b.title)+"</b><br>Author: "+escape(b.author)+"<br>Year: "+b.year+"<br>Available: "+b.available+"/"+b.total+"<br>ID: "+b.id+"</html>"), "Book Details", JOptionPane.INFORMATION_MESSAGE); }
    private void showMemberDetail(Member m){ JOptionPane.showMessageDialog(this,new JLabel("<html><b>"+escape(m.name)+"</b><br><span style='font-family:monospace;'>"+escape(m.id)+"</span></html>"), "Member Details", JOptionPane.INFORMATION_MESSAGE); }

    private void showLoanDetail(Loan l){
        int days = daysSince(l.issueDate);
        int fine = fineForLoan(l);
        int left = daysLeft(l);

        String msg = "<html>"
                + "<b>Loan ID:</b> " + shortId(l.loanId)
                + "<br><b>Book:</b> " + l.bookId
                + "<br><b>Member:</b> " + l.memberId
                + "<br><b>Issued:</b> " + days + " days ago"
                + "<br><b>Days Left:</b> <span style='color:"+(left<0?"red":"green")+"'>" + left + "</span>"
                + "<br><b>Fine:</b> <span style='color:"+(fine>0?"red":"black")+"'>₹" + fine + "</span>"
                + "</html>";

        JOptionPane.showMessageDialog(this,new JLabel(msg),"Loan Details",JOptionPane.INFORMATION_MESSAGE);
    }

    // ---------------- utility ----------------
    private int parseIntOrDefault(String s, int def){ try{return Integer.parseInt(s);}catch(Exception e){return def;} }
    private Book findBook(String id){ for(Book b: books) if(b.id.equals(id)) return b; return null; }
    private Member findMember(String id){ for(Member m: members) if(m.id.equals(id)) return m; return null; }
    private Loan findLoan(String id){ for(Loan l: loans) if(l.loanId.equals(id)) return l; return null; }
    private String shortId(String id){ if(id==null) return ""; return id.length()<=8? id : id.substring(0,8)+"..."; }
    private String escape(String s){ if(s==null) return ""; return s.replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;"); }
    private String indexToLetters(int index){ StringBuilder sb=new StringBuilder(); while(index>0){ index--; sb.insert(0,(char)('A'+(index%26))); index/=26; } return sb.toString(); }

    private void updateAfterChange(){ refreshAll(); saveData(); }

    // ---------------- Persistence ----------------
    @SuppressWarnings("unchecked")
    private boolean loadData(){
        File f = new File(DATA_FILE_NAME); if(!f.exists()) return false;
        try(ObjectInputStream ois=new ObjectInputStream(new FileInputStream(f))){
            Object o1=ois.readObject(), o2=ois.readObject(), o3=ois.readObject();
            if(o1 instanceof List && o2 instanceof List && o3 instanceof List){ books=(List<Book>)o1; members=(List<Member>)o2; loans=(List<Loan>)o3; return true; }
        }catch(Exception ex){ System.err.println("Load failed: "+ex.getMessage()); }
        return false;
    }

    private boolean saveData(){
        try(ObjectOutputStream oos=new ObjectOutputStream(new FileOutputStream(DATA_FILE_NAME))){
            oos.writeObject(books); oos.writeObject(members); oos.writeObject(loans); oos.flush(); return true;
        }catch(Exception ex){ System.err.println("Save failed: "+ex.getMessage()); return false; }
    }

    // ---------------- Sample data ----------------
    private void seedSampleData(){
        books.clear(); members.clear(); loans.clear();
        books.add(new Book("B001","Clean Code","Robert C. Martin",2008,3));
        books.add(new Book("B002","The Silent Patient","Alex Michaelides",2019,2));
        books.add(new Book("B003","Design Patterns","Gamma et al.",1994,1));
        books.add(new Book("B004","Introduction to Algorithms","CLRS",2009,4));
        books.add(new Book("B005","The Pragmatic Programmer","Andrew Hunt",1999,3));
        books.add(new Book("B006","Head First Java","Kathy Sierra",2005,5));
        books.add(new Book("B007","Effective Java","Joshua Bloch",2017,3));
        books.add(new Book("B008","Rich Dad Poor Dad","Robert Kiyosaki",1997,4));
        books.add(new Book("B009","Atomic Habits","James Clear",2018,6));
        books.add(new Book("B010","Harry Potter and the Sorcerer's Stone","J.K. Rowling",1997,5));
        books.add(new Book("B011","The Alchemist","Paulo Coelho",1988,4));
        books.add(new Book("B012","The Power of Your Subconscious Mind","Joseph Murphy",1963,3));
        books.add(new Book("B013","Java: The Complete Reference","Herbert Schildt",2021,2));
        books.add(new Book("B014","Sapiens: A Brief History of Humankind","Yuval Noah Harari",2011,3));
        books.add(new Book("B015","The Psychology of Money","Morgan Housel",2020,5));
        members.add(new Member("M01","Aisha Khan")); members.add(new Member("M02","Rohan Verma")); members.add(new Member("M03","Priya Shah"));

        // Example loan: create one real-time and one older to show fine calculation in sample
        loans.add(new Loan(UUID.randomUUID().toString(),"B001","M01"));
        Book b = findBook("B001"); if(b!=null) b.available = Math.max(0,b.available-1);

        // create an older loan (e.g., 40 days ago) to demonstrate overdue fine in sample
        long fortyDaysMs = System.currentTimeMillis() - (40L * 24 * 60 * 60 * 1000);
        loans.add(new Loan(UUID.randomUUID().toString(),"B002","M02", fortyDaysMs));
        Book b2 = findBook("B002"); if(b2!=null) b2.available = Math.max(0,b2.available-1);
    }

    // ---------------- Date & fine utilities ----------------
    /**
     * Returns number of whole days elapsed since timeMs to now.
     */
    private int daysSince(long timeMs){
        long diff = System.currentTimeMillis() - timeMs;
        return (int)(diff / (1000L*60*60*24));
    }

    /**
     * Fine rule: first 30 days free; every day beyond 30 costs ₹2 per day.
     */
    private int fineForLoan(Loan l){
        int days = daysSince(l.issueDate);
        return days > 30 ? (days - 30) * 2 : 0;
    }

    /**
     * Days left from 30-day allowance (can be negative if overdue).
     */
    private int daysLeft(Loan l){
        int d = daysSince(l.issueDate);
        return 30 - d;
    }

    // ---------------- main ----------------
    public static void main(String[] args){ SwingUtilities.invokeLater(() -> new LibraryAWT()); }
}
