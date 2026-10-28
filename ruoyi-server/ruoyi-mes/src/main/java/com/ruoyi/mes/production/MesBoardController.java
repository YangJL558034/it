package com.ruoyi.mes.production;

import com.ruoyi.common.annotation.Anonymous;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesBoardController {

  private final NamedParameterJdbcTemplate db;

  public MesBoardController(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  private List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
    return db
      .queryForList(sql, params)
      .stream()
      .map(MesRecords::external)
      .toList();
  }

  @Anonymous
  @GetMapping("/api/tv")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> board(
    @RequestParam(required = false) UUID lineId,
    @RequestParam(required = false) String workshop
  ) {
    var lines = db.queryForList(
      "select id,code,name,workshop from mes_line order by code",
      Map.of()
    );
    var selected = lines
      .stream()
      .filter(
        l ->
          (lineId == null || lineId.equals(l.get("id"))) &&
          (workshop == null ||
            workshop.isBlank() ||
            workshop.equals(l.get("workshop")))
      )
      .map(l -> l.get("id"))
      .toList();
    Map<String, Object> p = Map.of(
      "lines",
      selected.isEmpty() ? List.of(new UUID(0, 0)) : selected
    );
    String with =
      "with orders as(select * from mes_work_order where line_id in (:lines)), units as(select u.* from mes_unit u join mes_batch b on u.batch_id=b.id join orders o on b.work_order_id=o.id), tasks as(select t.* from mes_test_task t join units u on t.unit_id=u.id), latest as(select r.* from tasks t join mes_test_result r on r.test_task_id=t.id where not exists(select 1 from mes_test_task n where n.unit_id=t.unit_id and n.sequence>t.sequence)) ";
    var result = new LinkedHashMap<String, Object>();
    result.put("lines", lines);
    result.put("at", Instant.now());
    result.put(
      "planned",
      db.queryForObject(
        with + "select coalesce(sum(plan),0) from orders",
        p,
        Long.class
      )
    );
    result.put(
      "orderCount",
      db.queryForObject(with + "select count(*) from orders", p, Long.class)
    );
    result.put(
      "started",
      db.queryForObject(with + "select count(*) from units", p, Long.class)
    );
    result.put(
      "completed",
      db.queryForObject(
        with + "select count(*) from units where pack_code is not null",
        p,
        Long.class
      )
    );
    long passed = db.queryForObject(
      with + "select count(*) from latest where verdict='PASS'",
      p,
      Long.class
    ), tested = db.queryForObject(
      with + "select count(*) from latest",
      p,
      Long.class
    );
    result.put("passed", passed);
    result.put("tested", tested);
    result.put(
      "yield",
      tested == 0 ? 0 : Math.round((10000.0 * passed) / tested) / 100.0
    );
    result.put(
      "orders",
      rows(
        with +
        "select o.id,o.code,o.plan,o.line_id,(select count(*) from units u join mes_batch b on u.batch_id=b.id where b.work_order_id=o.id and u.pack_code is not null) as completed from orders o order by o.code desc limit 20",
        p
      )
    );
    result.put(
      "processes",
      rows(
        with +
        "select s.id,s.name,s.sequence,(select count(*) from mes_passage a join units u on a.unit_id=u.id where a.process_step_id=s.id) as completed from mes_process s order by s.sequence",
        p
      )
    );
    result.put(
      "stations",
      rows(
        "select id,code,name,line_id,process_step_id,coalesce(last_seen>now()-interval '90 seconds',false) as online from mes_station where line_id in (:lines) order by code",
        p
      )
    );
    result.put(
      "tests",
      rows(
        with +
        "select u.main_code,r.verdict,r.received_at from tasks t join mes_test_result r on r.test_task_id=t.id join units u on u.id=t.unit_id order by r.received_at desc limit 12",
        p
      )
    );
    result.put(
      "exceptions",
      rows(
        "select r.at,r.reason,d.code as device from mes_receipt r join mes_device d on r.device_id=d.id join mes_station s on d.station_id=s.id where s.line_id in (:lines) and r.status='Rejected' order by r.at desc limit 5",
        p
      )
    );
    result.put(
      "hourly",
      rows(
        with +
        ", hours as(select generate_series(date_trunc('hour',now())-interval '23 hours',date_trunc('hour',now()),interval '1 hour') as at), counts as(select date_trunc('hour',a.at) as at,count(*) filter(where s.sequence=1) as started,count(*) filter(where s.sequence=5) as completed from mes_passage a join units u on a.unit_id=u.id join mes_process s on s.id=a.process_step_id where a.at>=date_trunc('hour',now())-interval '23 hours' group by date_trunc('hour',a.at)) select h.at,coalesce(c.started,0) as started,coalesce(c.completed,0) as completed from hours h left join counts c on h.at=c.at order by h.at",
        p
      )
    );
    return result;
  }
}
