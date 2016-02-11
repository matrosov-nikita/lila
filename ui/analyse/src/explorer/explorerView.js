var m = require('mithril');
var chessground = require('chessground');
var partial = chessground.util.partial;
var classSet = chessground.util.classSet;
var renderConfig = require('./explorerConfig').view;

function resultBar(move) {
  var sum = move.white + move.draws + move.black;
  var section = function(key) {
    var percent = move[key] * 100 / sum;
    return percent === 0 ? null : m('span', {
      class: key,
      style: {
        width: (Math.round(move[key] * 1000 / sum) / 10) + '%'
      },
    }, percent > 12 ? Math.round(percent) + '%' : null);
  }
  return m('div.bar', ['white', 'draws', 'black'].map(section));
}

var lastShow = null;

function $trUci($tr) {
  return $tr[0] ? $tr[0].getAttribute('data-uci') : null;
}

function show(ctrl) {
  var data = ctrl.explorer.current();
  if (data) {
    lastShow = data.moves.length ? m('div.data', [
      m('table', [
        m('thead', [
          m('tr', [
            m('th', 'Move'),
            m('th', 'Games'),
            m('th', 'White / Draw / Black')
          ])
        ]),
        m('tbody', {
          config: function(el, isUpdate, ctx) {
            if (!isUpdate || ctx.lastFen === ctrl.vm.step.fen) return;
            ctx.lastFen = ctrl.vm.step.fen;
            setTimeout(function() {
              ctrl.explorer.setHoveringUci($trUci($(el).find('tr:hover')));
            }, 100);
          },
          onclick: function(e) {
            var $tr = $(e.target).parents('tr');
            if ($tr.length) ctrl.explorerMove($trUci($tr));
          },
          onmouseover: function(e) {
            var $tr = $(e.target).parents('tr');
            ctrl.explorer.setHoveringUci($trUci($tr));
          },
          onmouseout: function(e) {
            ctrl.explorer.setHoveringUci(null);
          }
        }, data.moves.map(function(move) {
          return m('tr', {
            key: move.uci,
            'data-uci': move.uci
          }, [
            m('td', move.san),
            m('td', lichess.numberFormat(move.total)),
            m('td', resultBar(move))
          ]);
        }))
      ])
    ]) : m('div.data.empty', 'No game found');
  }
  return lastShow;
}

function showConfig(ctrl) {
  return m('div.config', [
    m('div.title', ctrl.data.game.variant.name + ' opening explorer'),
    renderConfig(ctrl.explorer.config, ctrl.data.game.variant)
  ]);
}


var overlay = m('div.overlay', m.trust(lichess.spinnerHtml));

function failing() {
  return m('div.failing.message', [
    m('i[data-icon=,]'),
    m('h3', 'Oops, sorry!'),
    m('p', 'The explorer is temporarily'),
    m('p', 'out of service. Try again soon!')
  ]);
}

module.exports = {
  renderExplorer: function(ctrl) {
    if (!ctrl.explorer.enabled()) return;
    var config = ctrl.explorer.config;
    var configOpened = config.data.open();
    var loading = !configOpened && (ctrl.explorer.loading() || (!ctrl.explorer.current() && !ctrl.explorer.failing()));
    return m('div', {
      class: classSet({
        explorer_box: true,
        loading: loading,
        config: configOpened
      })
    }, [
      overlay,
      configOpened ? showConfig(ctrl) : (ctrl.explorer.failing() ? failing() : show(ctrl)),
      m('span.toconf', {
        'data-icon': configOpened ? 'L' : '%',
        onclick: config.toggleOpen
      })
    ]);
  }
};