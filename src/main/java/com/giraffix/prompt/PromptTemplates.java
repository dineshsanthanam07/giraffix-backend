package com.giraffix.prompt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Immutable prompt revisions with explicit publication. Runtime reads share one bounded cache per instance. */
@Service
public class PromptTemplates {
    public enum Kind {
        PRODUCT("product-analysis","Product analysis","product-analysis.txt"),
        CONCEPT("concept-analysis","Concept analysis","concept-analysis.txt"),
        PICKER("concept-picker","Concept picker","concept-picker.txt"),
        DIRECTOR("ad-creative-director","Creative director","ad-creative-director-v2.txt");
        public final String key,label,file;
        Kind(String key,String label,String file){this.key=key;this.label=label;this.file=file;}
        public static Kind from(String key){return Arrays.stream(values()).filter(k->k.key.equals(key)).findFirst()
                .orElseThrow(()->error(HttpStatus.NOT_FOUND,"Prompt template not found"));}
    }
    public record Revision(String key,int version,String content,String createdBy,Instant createdAt){}
    public record Summary(String key,String label,int activeVersion){}
    public record VersionInfo(String key,int version,String createdBy,Instant createdAt){}
    public record Publication(UUID id,int version,int previousVersion,String publishedBy,Instant publishedAt){}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Set<String> admins;
    private volatile Map<Kind,Revision> active=Map.of();
    private volatile boolean stale=true;
    private final Map<String,Revision> revisions=new LinkedHashMap<>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Revision> entry){return size()>256;}
    };

    public PromptTemplates(JdbcTemplate db,PlatformTransactionManager tm,
                           @Value("${giraffix.selection.admin-subjects:}") String admins){
        this.db=db;tx=new TransactionTemplate(tm);
        this.admins=new HashSet<>(Arrays.stream(admins.split(",")).map(String::trim).filter(s->!s.isBlank()).toList());
    }
    @jakarta.annotation.PostConstruct public void initialize() throws IOException {
        for(Kind kind:Kind.values()){
            String bundled;
            try(var input=new ClassPathResource("prompts/"+kind.file).getInputStream()){
                bundled=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }
            String content=bundled;
            tx.execute(status->{
                var row=db.queryForMap("SELECT active_version FROM prompt_templates WHERE prompt_key=? FOR UPDATE",kind.key);
                if(row.get("active_version")==null){
                    db.update("INSERT INTO prompt_template_revisions(prompt_key,version,content,created_by,created_at) VALUES(?,1,?,'system',?)",kind.key,content,Timestamp.from(Instant.now()));
                    db.update("UPDATE prompt_templates SET active_version=1 WHERE prompt_key=?",kind.key);
                }
                return null;
            });
        }
        refresh();
    }
    public void requireAdmin(String subject){
        if(!admins.contains(subject))throw error(HttpStatus.FORBIDDEN,"This operation requires a configured administrator");
    }
    public Revision current(Kind kind){
        if(stale)refresh();
        Revision revision=active.get(kind);
        if(revision==null)throw new IllegalStateException("Prompt templates have not been initialized");
        return revision;
    }
    public synchronized Revision version(Kind kind,int version){
        String key=kind.key+":"+version;
        Revision cached=revisions.get(key);
        if(cached!=null)return cached;
        Revision current=active.get(kind);
        if(current!=null && current.version()==version)return current;
        Revision found=db.query("SELECT prompt_key,version,content,created_by,created_at FROM prompt_template_revisions WHERE prompt_key=? AND version=?",
                this::map,kind.key,version).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"Prompt version not found"));
        revisions.put(key,found);return found;
    }
    private synchronized void refresh(){
        if(!stale)return;
        var loaded=new EnumMap<Kind,Revision>(Kind.class);
        for(Revision revision:db.query("SELECT r.prompt_key,r.version,r.content,r.created_by,r.created_at FROM prompt_templates t JOIN prompt_template_revisions r ON r.prompt_key=t.prompt_key AND r.version=t.active_version",this::map)){
            loaded.put(Kind.from(revision.key()),revision);
            revisions.put(revision.key()+":"+revision.version(),revision);
        }
        active=Map.copyOf(loaded);stale=false;
    }
    /** Notifications are hints; refresh lazily once, coalescing a burst of edits. */
    public synchronized void changed(String key,int version){
        Revision cached=active.get(Kind.from(key));
        if(cached==null || cached.version()!=version)stale=true;
    }
    /** A reconnect can have missed notifications, so re-read before using current templates. */
    public synchronized void invalidate(){stale=true;}
    public List<Summary> list(String subject){
        requireAdmin(subject);
        return db.query("SELECT prompt_key,active_version FROM prompt_templates ORDER BY prompt_key",
                (r,n)->new Summary(r.getString(1),Kind.from(r.getString(1)).label,r.getInt(2)));
    }
    public Revision get(String subject,Kind kind){
        requireAdmin(subject);
        return db.query("SELECT r.prompt_key,r.version,r.content,r.created_by,r.created_at FROM prompt_templates t JOIN prompt_template_revisions r ON r.prompt_key=t.prompt_key AND r.version=t.active_version WHERE t.prompt_key=?",
                this::map,kind.key).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"Prompt template not found"));
    }
    public List<VersionInfo> history(String subject,Kind kind,int page,int size){
        requireAdmin(subject);page(page,size);
        return db.query("SELECT prompt_key,version,created_by,created_at FROM prompt_template_revisions WHERE prompt_key=? ORDER BY version DESC LIMIT ? OFFSET ?",
                (r,n)->new VersionInfo(r.getString(1),r.getInt(2),r.getString(3),r.getTimestamp(4).toInstant()),kind.key,size,(long)page*size);
    }
    public List<Publication> publications(String subject,Kind kind,int page,int size){
        requireAdmin(subject);page(page,size);
        return db.query("SELECT id,version,previous_version,published_by,published_at FROM prompt_publications WHERE prompt_key=? ORDER BY published_at DESC,id LIMIT ? OFFSET ?",
                (r,n)->new Publication(r.getObject(1,UUID.class),r.getInt(2),r.getInt(3),r.getString(4),r.getTimestamp(5).toInstant()),kind.key,size,(long)page*size);
    }
    public Revision draft(String subject,Kind kind,String content,int expectedActiveVersion){
        requireAdmin(subject);validate(kind,content);
        return tx.execute(status->{
            checkActive(kind,expectedActiveVersion);
            int next=db.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM prompt_template_revisions WHERE prompt_key=?",Integer.class,kind.key);
            Instant now=Instant.now();
            db.update("INSERT INTO prompt_template_revisions(prompt_key,version,content,created_by,created_at) VALUES(?,?,?,?,?)",kind.key,next,content,subject,Timestamp.from(now));
            return new Revision(kind.key,next,content,subject,now);
        });
    }
    public synchronized Revision publish(String subject,Kind kind,int version,int expectedActiveVersion){
        requireAdmin(subject);
        Revision result=tx.execute(status->{
            checkActive(kind,expectedActiveVersion);
            Revision target=version(kind,version);
            validate(kind,target.content());
            if(version!=expectedActiveVersion){
                db.update("UPDATE prompt_templates SET active_version=? WHERE prompt_key=?",version,kind.key);
                db.update("INSERT INTO prompt_publications(id,prompt_key,version,previous_version,published_by,published_at) VALUES(?,?,?,?,?,?)",
                        UUID.randomUUID(),kind.key,version,expectedActiveVersion,subject,Timestamp.from(Instant.now()));
            }
            return target;
        });
        // Use the committed result directly: no re-read and no timer. Other replicas receive NOTIFY.
        var updated=new EnumMap<Kind,Revision>(Kind.class);updated.putAll(active);updated.put(kind,result);
        active=Map.copyOf(updated);revisions.put(kind.key+":"+result.version(),result);return result;
    }
    private void checkActive(Kind kind,int expected){
        Integer actual=db.queryForObject("SELECT active_version FROM prompt_templates WHERE prompt_key=? FOR UPDATE",Integer.class,kind.key);
        if(actual==null || actual!=expected)throw error(HttpStatus.CONFLICT,"The active prompt changed; reload before saving or publishing");
    }
    private void validate(Kind kind,String content){
        if(content==null || content.isBlank() || content.length()>60000 || content.indexOf('\0')>=0)
            throw error(HttpStatus.BAD_REQUEST,"Prompt must contain 1 to 60000 characters and no NUL characters");
        if(kind==Kind.PICKER && !content.contains("{{MINIMUM_COMPATIBILITY_SCORE}}"))
            throw error(HttpStatus.BAD_REQUEST,"Concept picker must retain {{MINIMUM_COMPATIBILITY_SCORE}}");
        var placeholders=java.util.regex.Pattern.compile("\\{\\{[^{}]+}}").matcher(content);
        while(placeholders.find())if(kind!=Kind.PICKER || !placeholders.group().equals("{{MINIMUM_COMPATIBILITY_SCORE}}"))
            throw error(HttpStatus.BAD_REQUEST,"Unsupported prompt placeholder: "+placeholders.group());
    }
    private void page(int page,int size){if(page<0 || page>1000000 || size<1 || size>50)throw error(HttpStatus.BAD_REQUEST,"Invalid pagination");}
    private Revision map(java.sql.ResultSet r,int row) throws java.sql.SQLException {
        return new Revision(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant());
    }
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
