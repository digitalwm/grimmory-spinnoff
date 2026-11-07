import {Component, computed, ElementRef, EventEmitter, inject, Input, OnChanges, Output, signal, ViewChild} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {DestroyRef} from '@angular/core';
import {Button} from '@openng/optimus-ui/button';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {UserService} from '../../../settings/user-management/user.service';
import {
  CatalogueGroup, CataloguePreview, CatalogueReceipt, CatalogueReceiptSummary, CatalogueReconciliationService, CatalogueScan, QuarantinedCopy,
} from '../../service/catalogue-reconciliation.service';

@Component({
  selector: 'app-catalogue-reconciliation',
  imports: [FormsModule, Button, TranslocoDirective],
  providers: [CatalogueReconciliationService],
  templateUrl: './catalogue-reconciliation.component.html',
  styleUrl: './catalogue-reconciliation.component.scss',
})
export class CatalogueReconciliationComponent implements OnChanges {
  @Input({required: true}) libraryId!: number;
  @Input() reviewGroup: CatalogueGroup | null = null;
  @Output() reconciled = new EventEmitter<CatalogueReceipt>();
  @Output() mergingChange = new EventEmitter<boolean>();
  @ViewChild('previewPanel') private previewPanel?: ElementRef<HTMLElement>;
  private readonly service = inject(CatalogueReconciliationService);
  private readonly userService = inject(UserService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  readonly isAdmin = computed(() => this.userService.currentUser()?.permissions.admin === true);
  readonly report = signal<CatalogueScan | null>(null);
  readonly inspected = signal<CataloguePreview | null>(null);
  readonly lastReceipt = signal<CatalogueReceipt | null>(null);
  readonly quarantined = signal<QuarantinedCopy[]>([]);
  search = '';
  readonly history = signal<CatalogueReceiptSummary[]>([]);
  readonly busy = signal(false);
  readonly merging = signal(false);
  readonly error = signal<string | null>(null);
  selectedGroup: CatalogueGroup | null = null;
  targetBookId: number | null = null;
  confirmed = false;
  private generation = 0;

  ngOnChanges(): void {
    this.generation++;
    this.report.set(null);
    this.inspected.set(null);
    this.selectedGroup = null;
    this.confirmed = false;
    this.busy.set(false);
    this.error.set(null);
    this.history.set([]);
    this.quarantined.set([]);
    if (this.reviewGroup) this.inspect(this.reviewGroup);
  }

  scan(page = 0): void {
    if (!this.isAdmin() || this.busy() || this.merging()) return;
    const generation = ++this.generation;
    this.busy.set(true);
    this.error.set(null);
    this.inspected.set(null);
    this.selectedGroup = null;
    this.confirmed = false;
    this.service.scan(this.libraryId, page, this.search).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: report => {
        if (generation !== this.generation) return;
        this.report.set(report);
        this.busy.set(false);
        if (report.totalGroups > 0 && report.groups.length === 0 && page > 0) this.scan(page - 1);
      },
      error: err => this.fail(err, generation),
    });
  }

  inspect(group: CatalogueGroup, target = group.suggestedTargetBookId): void {
    if (this.busy() || this.merging() || !this.isAdmin()) return;
    const changedGroup = this.selectedGroup?.key !== group.key;
    const generation = ++this.generation;
    this.selectedGroup = group;
    this.targetBookId = target;
    this.confirmed = false;
    this.inspected.set(null);
    this.error.set(null);
    this.busy.set(true);
    this.service.preview(this.libraryId, group, target).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: preview => {
        if (generation !== this.generation) return;
        this.selectedGroup = {...group, members: preview.members};
        this.inspected.set(preview);
        this.busy.set(false);
        if (changedGroup) setTimeout(() => this.previewPanel?.nativeElement.focus(), 0);
      },
      error: err => this.fail(err, generation),
    });
  }

  selectTarget(id: number): void {
    if (this.selectedGroup) this.inspect(this.selectedGroup, id);
  }

  merge(): void {
    const preview = this.inspected();
    if (!this.isAdmin() || !this.confirmed || !preview?.eligible || this.busy() || this.merging()) return;
    this.merging.set(true);
    this.mergingChange.emit(true);
    this.error.set(null);
    this.service.merge(preview).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: receipt => {
        this.lastReceipt.set(receipt);
        this.reconciled.emit(receipt);
        this.loadQuarantine(receipt.id);
        this.confirmed = false;
        this.inspected.set(null);
        this.merging.set(false);
        this.mergingChange.emit(false);
        this.scan(this.report()?.page ?? 0);
      },
      error: err => {
        this.merging.set(false);
        this.mergingChange.emit(false);
        this.confirmed = false;
        this.inspected.set(null);
        this.error.set(err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed'));
      },
    });
  }

  loadHistory(): void {
    if (!this.isAdmin()) return;
    const library = this.libraryId;
    this.service.receipts(library).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: receipts => {if (library === this.libraryId) this.history.set(receipts);},
      error: err => this.error.set(err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed')),
    });
  }

  loadQuarantine(id: string): void {
    const library = this.libraryId;
    this.service.receipt(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: data => {if (library === this.libraryId) this.quarantined.set((data['quarantine'] as QuarantinedCopy[]) ?? []);},
      error: err => this.error.set(err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed')),
    });
  }

  downloadCopy(copy: QuarantinedCopy): void {
    this.service.quarantinedFile(copy.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob), link = document.createElement('a');
        link.href = url; link.download = copy.original_path.split('/').at(-1) || 'recovered-book'; link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: () => this.error.set(this.t.translate('book.catalogueReconciliation.copyUnavailable')),
    });
  }

  downloadReceipt(id?: string): void {
    const receiptId = id ?? this.lastReceipt()?.id;
    if (!receiptId) return;
    this.service.receipt(receiptId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: data => {
        const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'}));
        const link = document.createElement('a');
        link.href = url;
        link.download = `catalogue-reconciliation-${receiptId}.json`;
        link.click();
        URL.revokeObjectURL(url);
      },
      error: err => this.error.set(err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed')),
    });
  }

  relatedEntries(preview: CataloguePreview): {label: string; count: number}[] {
    return Object.entries(preview.relatedRecords).filter(([, count]) => count > 0)
      .map(([table, count]) => ({label: this.t.translate(`book.catalogueReconciliation.related.${table}`), count}));
  }

  differenceEntries(preview: CataloguePreview): {field: string; values: Record<string, string | null>}[] {
    return Object.entries(preview.metadataDifferences).map(([field, values]) => ({field: field.replaceAll('_', ' '), values}));
  }

  private fail(err: {error?: {message?: string}}, generation: number): void {
    if (generation !== this.generation) return;
    this.busy.set(false);
    this.error.set(err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed'));
  }
}
