const { request } = require('./request');

/**
 * 埋点上报（PRD §12）。失败静默，不影响主流程。
 * 核心事件：app_open / link_detected / link_parse_submit / link_parse_success /
 * link_parse_fail / analysis_view / price_detail_expand / risk_detail_view /
 * watch_create / target_price_update / purchase_click / ai_chat_send /
 * feedback_submit / share_card_click
 */
function event(name, props = {}) {
  request({
    url: '/api/track/event',
    method: 'POST',
    data: { event: name, props },
    auth: true,
  }).catch(() => {});
}

module.exports = { event };
