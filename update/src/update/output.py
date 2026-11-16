"""Human-readable CLI reports rendered with Rich."""
from collections import Counter, defaultdict

from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.text import Text


_ACTION_STATUSES = {'update-available', 'unknown', 'unavailable', 'unsupported',
                    'divergent', 'incompatible', 'not-ready'}
_KIND_LABELS = {
    'action': 'GitHub Actions', 'cluster-observation': 'Osservazioni cluster',
    'docker-base': 'Immagini base Docker', 'go-dependency': 'Dipendenze Go',
    'go-locked': 'Versioni Go risolte', 'go-toolchain': 'Toolchain Go',
    'helm-chart': 'Chart Helm', 'helm-repository': 'Repository Helm',
    'image': 'Immagini container', 'java-toolchain': 'Toolchain Java',
    'k3s': 'K3s', 'k3s-component': 'Componenti inclusi in K3s',
    'maven-dependency': 'Dipendenze Maven', 'maven-managed': 'Dipendenze Maven gestite',
    'maven-parent': 'Parent Maven', 'maven-plugin': 'Plugin Maven',
    'python-dependency': 'Dipendenze Python', 'python-locked': 'Versioni Python risolte',
    'python-local-project': 'Progetti Python locali', 'rust-dependency': 'Crate Rust',
    'rust-locked': 'Versioni Rust risolte', 'rust-toolchain': 'Toolchain Rust',
}
_STATUS_LABELS = {
    'ahead': 'Più recente della fonte', 'configured': 'Configurato', 'current': 'Aggiornato',
    'divergent': 'Stato divergente', 'incompatible': 'Incompatibile',
    'not-ready': 'Non pronto', 'parent-managed': 'Gestito dal parent', 'unknown': 'Da verificare',
    'unavailable': 'Fonte non disponibile', 'unsupported': 'Non supportato',
    'update-available': 'Aggiornamento disponibile',
}
_ACTION_LABELS = {
    'divergent': 'Divergente', 'incompatible': 'Incompatibile',
    'not-ready': 'Non pronto', 'unknown': 'Verificare',
    'unavailable': 'Fonte assente', 'unsupported': 'Non supportato',
    'update-available': 'Aggiornare',
}
_STATUS_STYLES = {
    'update-available': 'bold yellow', 'unknown': 'yellow',
    'unavailable': 'bold red', 'unsupported': 'bold red',
    'divergent': 'bold magenta', 'incompatible': 'bold red', 'not-ready': 'bold red',
}


def _kind_label(kind):
    return _KIND_LABELS.get(kind, kind.replace('-', ' ').capitalize())


def _status_label(status):
    return _STATUS_LABELS.get(status, status.replace('-', ' ').capitalize())


def _as_text(value, style=None):
    return Text(str(value) if value not in (None, '') else '—', style=style)


def _scope(component):
    if component.clusters:
        return ', '.join(component.clusters)
    return 'Ambito non risolto' if component.source.startswith('clusters/') else 'Repository'


def render_report(report, console=None, error_console=None):
    """Render inventory reports as grouped action tables; keep JSON elsewhere."""
    console = console or Console()
    error_console = error_console or Console(stderr=True)
    if report.command in ('status', 'check'):
        _render_inventory(report, console)
    else:
        if report.diff:
            console.print(Text(report.diff), end='')
            console.print(Text('Cluster impattati: ' + (', '.join(report.affected_clusters) or 'repository')))
            console.print(Text('Anteprima: ' + report.fingerprint))
            console.print(Text('Modifiche locali applicate.' if report.written else 'Anteprima: nessuna modifica scritta.'))
    for error in report.errors:
        error_console.print(Text('Errore: ', style='bold red') + Text(error))


def _render_inventory(report, console):
    counts = Counter(component.status for component in report.components)
    actions = [component for component in report.components if component.status in _ACTION_STATUSES]
    no_action = len(report.components) - len(actions)
    summary = Text(f"Componenti: {len(report.components)}   |   Da verificare: {len(actions)}   |   "
                   f"Senza azione immediata: {no_action}")
    if report.errors:
        summary.append(f"   |   Segnalazioni: {len(report.errors)}", style='yellow')
    console.print(Panel(summary, title='Riepilogo', border_style='blue'))

    console.rule('Azioni da prendere')
    if not actions:
        console.print(Text('Nessuna azione richiesta in base ai risultati disponibili.', style='green'))
    else:
        grouped = defaultdict(list)
        for component in actions:
            grouped[component.kind].append(component)
        for kind in sorted(grouped, key=lambda value: _kind_label(value).casefold()):
            items = sorted(grouped[kind], key=lambda item: (item.name.casefold(), item.source.casefold()))
            console.rule(Text(f'{_kind_label(kind)} · {len(items)}'))
            table = Table(expand=True, show_lines=False, header_style='bold cyan')
            table.add_column('Azione', min_width=13)
            table.add_column('Componente', min_width=14)
            table.add_column('Versioni', min_width=18)
            table.add_column('Ambito', min_width=10)
            table.add_column('Origine / dettaglio', min_width=20)
            for item in items:
                style = _STATUS_STYLES.get(item.status)
                version = f'{item.declared or "—"} → {item.candidate or item.observed or "—"}'
                note = item.source + (f' · {item.detail}' if item.detail else '')
                table.add_row(_as_text(_ACTION_LABELS.get(item.status, _status_label(item.status)), style),
                              _as_text(item.name),
                              _as_text(version), _as_text(_scope(item)), _as_text(note))
            console.print(table)

    console.rule('Nessuna azione immediata')
    quiet_counts = [(status, count) for status, count in sorted(counts.items())
                    if status not in _ACTION_STATUSES]
    if quiet_counts:
        console.print(Text('   ·   '.join(f'{_status_label(status)}: {count}'
                                          for status, count in quiet_counts), style='dim'))
    else:
        console.print(Text('Nessun componente in questa sezione.', style='dim'))