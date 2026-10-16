package com.ruoyi.mes.service;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.ISysDeptService;

/** MES relationships and change protection live here, independently of RuoYi CRUD tooling. */
@Service
public class MesMasterService {
    private final NamedParameterJdbcTemplate db;
    private final ISysDeptService departments;
    private record Spec(String table, List<String> fields, List<String> keys) {}
    private static final Map<String,Spec> SPECS=Map.of(
        "lines",new Spec("mes_line",List.of("code","name","workshop","dept_id"),List.of("code","dept_id")),
        "products",new Spec("mes_product",List.of("code","name","dept_id"),List.of("code","dept_id")),
        "stations",new Spec("mes_station",List.of("code","name","line_id","process_step_id"),List.of("code","line_id","process_step_id")),
        "orders",new Spec("mes_work_order",List.of("code","line_id","product_id","plan","status"),List.of("code","line_id","product_id")),
        "batches",new Spec("mes_batch",List.of("code","work_order_id","plan"),List.of("code","work_order_id")));
    public MesMasterService(NamedParameterJdbcTemplate db,ISysDeptService departments){this.db=db;this.departments=departments;}
    private Spec spec(String resource){var s=SPECS.get(resource);if(s==null)throw new ServiceException("未知的 MES 资料类型",404);return s;}
    private static String camel(String name){var out=new StringBuilder();boolean upper=false;for(char c:name.toCharArray()){if(c=='_'){upper=true;continue;}out.append(upper?Character.toUpperCase(c):c);upper=false;}return out.toString();}
    private static Map<String,Object> external(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();row.forEach((k,v)->result.put(camel(k),v));return result;}
    private Map<String,Object> scope(){var query=new SysDept();query.setStatus("0");var ids=departments.selectDeptList(query).stream().map(SysDept::getDeptId).toList();return new HashMap<>(Map.of("deptIds",ids.isEmpty()?List.of(-1L):ids));}
    public Map<String,Object> list(String resource,String keyword,int page,int size){
        var s=spec(resource);var p=scope();p.put("q","%"+(keyword==null?"":keyword.strip())+"%");p.put("limit",Math.min(200,Math.max(1,size)));p.put("offset",Math.max(0L,(long)page-1)*(int)p.get("limit"));
        String filter=" where dept_id in (:deptIds) and (code ilike :q"+(s.fields.contains("name")?" or name ilike :q":"")+")";
        var total=db.queryForObject("select count(*) from "+s.table+filter,p,Long.class);
        var rows=db.queryForList("select * from "+s.table+filter+" order by updated_at desc,id limit :limit offset :offset",p).stream().map(MesMasterService::external).toList();
        return Map.of("rows",rows,"total",total);
    }
    private Map<String,Object> row(String resource,UUID id){var p=scope();p.put("id",id);var rows=db.queryForList("select * from "+spec(resource).table+" where id=:id and dept_id in (:deptIds)",p);if(rows.isEmpty())throw new ServiceException("记录不存在或不在授权数据范围",403);return rows.get(0);}
    public Map<String,Object> options(String resource){
        spec(resource);var p=scope();var result=new LinkedHashMap<String,Object>();
        List<String> related=switch(resource){case "stations"->List.of("lines");case "orders"->List.of("lines","products");case "batches"->List.of("orders");default->List.of();};
        // A lookup grants only the identifiers/labels required by this form, never another module's full records.
        for(var kind:related)result.put(kind,db.queryForList("select id,code"+(spec(kind).fields.contains("name")?",name":"")+" from "+spec(kind).table+" where dept_id in (:deptIds) order by code limit 10000",p).stream().map(MesMasterService::external).toList());
        if(resource.equals("lines")||resource.equals("products"))result.put("departments",departments.selectDeptList(new SysDept()).stream().map(d->Map.of("id",d.getDeptId(),"name",d.getDeptName())).toList());
        if(resource.equals("stations"))result.put("processes",processes());return result;
    }
    public List<Map<String,Object>> processes(){return db.queryForList("select id,sequence,name from mes_process order by sequence",Map.of());}
    private String text(Map<String,Object> input,String field){Object v=input.get(camel(field));if(!(v instanceof String str)||str.isBlank()||str.strip().length()>128)throw new ServiceException(camel(field)+" 必填，最多 128 字符",422);return str.strip();}
    private int integer(Object value,String field){if(!(value instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<1||n.longValue()>Integer.MAX_VALUE)throw new ServiceException(field+" 必须为正整数",422);return n.intValue();}
    private UUID uuid(Object value){try{return UUID.fromString(String.valueOf(value));}catch(Exception e){throw new ServiceException("关联编号无效",422);}}
    private boolean referenced(String resource,UUID id){
        String sql=switch(resource){
            case "lines"->"select exists(select 1 from mes_station where line_id=:id union all select 1 from mes_work_order where line_id=:id)";
            case "products"->"select exists(select 1 from mes_work_order where product_id=:id)";
            case "orders"->"select exists(select 1 from mes_batch where work_order_id=:id)";
            default->"select false";
        };
        return Boolean.TRUE.equals(db.queryForObject(sql,Map.of("id",id),Boolean.class)) || db.queryForObject("select count(*) from mes_legacy_guard where resource=:resource and row_id=:id",Map.of("resource",resource,"id",id),Long.class)>0;
    }
    @Transactional
    public Map<String,Object> save(String resource,UUID id,Map<String,Object> input){
        var s=spec(resource);db.getJdbcTemplate().execute("select pg_advisory_xact_lock(74215003)");
        Map<String,Object> before=id==null?null:row(resource,id);var values=new LinkedHashMap<String,Object>();
        var allowed=new HashSet<>(s.fields.stream().map(MesMasterService::camel).toList());allowed.add("version");
        if(!allowed.containsAll(input.keySet()))throw new ServiceException("包含不允许修改的字段",422);
        for(String field:s.fields){
            Object value=switch(field){
                case "plan"->integer(input.get("plan"),"计划数量");
                case "dept_id"->(long)integer(input.get("deptId"),"所属部门");
                case "line_id","product_id","process_step_id","work_order_id"->uuid(input.get(camel(field)));
                default->text(input,field);
            };values.put(field,value);
        }
        if(before!=null){
            values.put("expectedVersion",integer(input.get("version"),"记录版本"));
            if(!Objects.equals(before.get("version"),values.get("expectedVersion")))throw new ServiceException("资料已被其他人修改，请刷新后重试",409);
            boolean keyChanged=s.keys.stream().anyMatch(k->!Objects.equals(before.get(k),values.get(k)));
            if(keyChanged&&referenced(resource,id))throw new ServiceException("已有下游关联或生产历史，禁止变更关键关联",409);
        }
        long dept;
        if(resource.equals("lines")||resource.equals("products")){
            dept=(long)values.get("dept_id");if(!((List<?>)scope().get("deptIds")).contains(dept))throw new ServiceException("所属部门不在授权范围",403);
        }else if(resource.equals("batches")){
            var order=row("orders",(UUID)values.get("work_order_id"));dept=((Number)order.get("dept_id")).longValue();
            if(!"Open".equals(order.get("status")))throw new ServiceException("工单已关闭，不能维护批次",409);
            long allocated=db.queryForObject("select coalesce(sum(plan),0) from mes_batch where work_order_id=:orderId and id<>:id",Map.of("orderId",values.get("work_order_id"),"id",id==null?new UUID(0,0):id),Long.class);
            if(allocated+(int)values.get("plan")>((Number)order.get("plan")).longValue())throw new ServiceException("批次数量合计不得超过工单计划",422);
            if(id!=null&&db.queryForObject("select coalesce(max(produced),0) from mes_legacy_guard where resource='batches' and row_id=:id",Map.of("id",id),Integer.class)>(int)values.get("plan"))throw new ServiceException("批次计划不得少于已投入的产品数",422);
        }else{
            var line=row("lines",(UUID)values.get("line_id"));dept=((Number)line.get("dept_id")).longValue();
            if(resource.equals("stations")){
                if(db.queryForObject("select count(*) from mes_process where id=:id",Map.of("id",values.get("process_step_id")),Long.class)==0)throw new ServiceException("工序不存在",422);
            }else{
                var product=row("products",(UUID)values.get("product_id"));if(((Number)product.get("dept_id")).longValue()!=dept)throw new ServiceException("产品与产线必须属于同一数据范围部门",422);
                if(!List.of("Open","Closed").contains(values.get("status")))throw new ServiceException("工单状态无效",422);
                if(id!=null){
                    if(db.queryForObject("select coalesce(sum(plan),0) from mes_batch where work_order_id=:id",Map.of("id",id),Long.class)>(int)values.get("plan"))throw new ServiceException("工单计划不能少于已分配批次数量",422);
                    if("Closed".equals(values.get("status"))&&db.queryForObject("select coalesce(sum(pending_tests),0) from mes_legacy_guard where (resource='orders' and row_id=:id) or (resource='batches' and row_id in (select id from mes_batch where work_order_id=:id))",Map.of("id",id),Long.class)>0)throw new ServiceException("存在未完成测试任务，不能关闭工单",409);
                }
            }
        }
        values.put("dept_id",dept);values.put("id",id==null?UUID.randomUUID():id);
        var columns=new ArrayList<>(s.fields);if(!columns.contains("dept_id"))columns.add("dept_id");
        if(id==null){values.put("created_by",SecurityUtils.getUserId());columns.add("id");columns.add("created_by");db.update("insert into "+s.table+" ("+String.join(",",columns)+") values ("+String.join(",",columns.stream().map(k->":"+k).toList())+")",values);}
        else{int updated=db.update("update "+s.table+" set "+String.join(",",columns.stream().map(k->k+"=:"+k).toList())+", version=version+1,updated_at=now() where id=:id and version=:expectedVersion",values);if(updated!=1)throw new ServiceException("资料已变更，请刷新",409);}
        return external(row(resource,(UUID)values.get("id")));
    }
    @Transactional
    public void delete(String resource,UUID id,int version){
        db.getJdbcTemplate().execute("select pg_advisory_xact_lock(74215003)");var before=row(resource,id);
        if(((Number)before.get("version")).intValue()!=version)throw new ServiceException("资料已变更，请刷新",409);
        if(referenced(resource,id))throw new ServiceException("已有下游关联或生产历史，禁止删除",409);
        db.update("delete from "+spec(resource).table+" where id=:id and version=:version",Map.of("id",id,"version",version));
    }
}
