-- 优化催还 / 逾期通知模板：统一为正式、规范的公文式措辞，并修正 DUE_SOON 提前天数为正数
-- （原 offset_days = -3 既在配置页显示为负号，又使"即将到期"提醒实际在到期后触发，属语义错误）

UPDATE notification_rule
SET subject_tpl = CASE event_type
        WHEN 'DUE_SOON'   THEN '【借阅提醒】您借阅的图书即将到期'
        WHEN 'OVERDUE_D1' THEN '【逾期提醒】您借阅的图书已逾期，请尽快归还'
        WHEN 'OVERDUE_D3' THEN '【逾期催告】请尽快归还逾期图书'
        WHEN 'OVERDUE_D7' THEN '【严重逾期告知】请立即处理逾期图书'
    END,
    body_tpl = CASE event_type
        WHEN 'DUE_SOON' THEN '尊敬的{readerName}读者：您借阅的《{bookTitle}》（借阅号：{borrowNo}）将于 {dueDate} 到期。请您合理安排时间，按期到馆归还或办理续借手续，以免产生滞纳金。感谢您的配合。'
        WHEN 'OVERDUE_D1' THEN '尊敬的{readerName}读者：您借阅的《{bookTitle}》（借阅号：{borrowNo}）已逾期 {daysOverdue} 天，请尽快到馆办理归还手续。逾期将按馆内规定计收滞纳金，敬请留意。'
        WHEN 'OVERDUE_D3' THEN '尊敬的{readerName}读者：您借阅的《{bookTitle}》（借阅号：{borrowNo}）已逾期 {daysOverdue} 天，至今尚未归还。请尽快至图书馆服务台办理归还或续借；如长期逾期未处理，将影响您的后续借阅权限。'
        WHEN 'OVERDUE_D7' THEN '尊敬的{readerName}读者：您借阅的《{bookTitle}》（借阅号：{borrowNo}）已严重逾期 {daysOverdue} 天。长期逾期将影响您的借阅权限并可能产生相应费用，请立即至图书馆服务台处理。如有疑问请联系服务台。'
    END,
    offset_days = CASE event_type WHEN 'DUE_SOON' THEN 3 ELSE offset_days END;
