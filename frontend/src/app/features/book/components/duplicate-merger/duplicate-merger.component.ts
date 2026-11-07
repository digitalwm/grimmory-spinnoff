import {ChangeDetectorRef, Component, inject, OnDestroy, OnInit, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {DynamicDialogConfig, DynamicDialogRef} from '@openng/optimus-ui/dynamicdialog';
import {Button} from '@openng/optimus-ui/button';
import {Checkbox} from '@openng/optimus-ui/checkbox';
import {RadioButton} from '@openng/optimus-ui/radiobutton';
import {SelectButton} from '@openng/optimus-ui/selectbutton';
import {ProgressBar} from '@openng/optimus-ui/progressbar';
import {Tag} from '@openng/optimus-ui/tag';
import {Paginator, PaginatorState} from '@openng/optimus-ui/paginator';
import {lastValueFrom, Subject, takeUntil} from 'rxjs';
import {BookFileService} from '../../service/book-file.service';
import {BookService} from '../../service/book.service';
import {Book, DuplicateDetectionRequest, DuplicateGroup} from '../../model/book.model';
import {ConfirmationService, MessageService} from '@openng/optimus-ui/api';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {CoverComponent} from '../../../../shared/components/cover/cover.component';
import {UrlHelperService} from '../../../../shared/service/url-helper.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {CatalogueGroup, CatalogueReceipt, CatalogueReconciliationService, QuickMergeResult} from '../../service/catalogue-reconciliation.service';
import {CatalogueReconciliationComponent} from '../catalogue-reconciliation/catalogue-reconciliation.component';

type PresetMode = 'strict' | 'balanced' | 'aggressive' | 'custom';

interface DisplayGroup extends DuplicateGroup {
  selectedTargetBookId: number;
  dismissed: boolean;
  selectedForDeletion: Set<number>;
  reviewAssessment?: QuickMergeResult;
  error?: string;
}

@Component({
  selector: 'app-duplicate-merger',
  standalone: true,
  imports: [
    FormsModule,
    Button,
    Checkbox,
    RadioButton,
    SelectButton,
    ProgressBar,
    Tag,
    Paginator,
    TranslocoDirective,
    TranslocoPipe,
    CoverComponent,
    CatalogueReconciliationComponent,
  ],
  providers: [CatalogueReconciliationService],
  templateUrl: './duplicate-merger.component.html',
  styleUrls: ['./duplicate-merger.component.scss']
})
export class DuplicateMergerComponent implements OnInit, OnDestroy {
  private readonly reconciliation = inject(CatalogueReconciliationService);
  private disposed = false;
  private readonly bookFileService = inject(BookFileService);
  private readonly bookService = inject(BookService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly dialogRef = inject(DynamicDialogRef);
  private readonly config = inject(DynamicDialogConfig);
  private readonly t = inject(TranslocoService);
  readonly urlHelper = inject(UrlHelperService);
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly changeDetectorRef = inject(ChangeDetectorRef);
  libraryId!: number;
  mode: 'formats' | 'catalogue' = 'formats';
  catalogueMerging = false;
  reviewedGroup: CatalogueGroup | null = null;
  reviewAssessment: QuickMergeResult | null = null;
  resolvedBookIds: number[] = [];
  separatedBookIds: number[] = [];
  readonly stopRequested = signal(false);
  readonly batchRunning = signal(false);
  reviewedGroupFinished = false;
  presetMode: PresetMode = 'balanced';
  showAdvanced = false;

  matchByIsbn = true;
  matchByExternalId = true;
  matchByTitleAuthor = true;
  matchByDirectory = false;
  matchByFilename = false;

  readonly isScanning = signal(false);
  readonly isMerging = signal(false);
  readonly hasScanned = signal(false);
  moveFiles = false;
  readonly mergeProgress = signal(0);
  readonly mergeTotal = signal(0);

  groups: DisplayGroup[] = [];
  presetOptions: { label: string; value: PresetMode }[] = [];

  pageFirst = 0;
  pageSize = 20;

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.libraryId = this.config.data.libraryId;

    const settings = this.appSettingsService.appSettings();
    if (settings) {
      this.moveFiles = settings.metadataPersistenceSettings?.moveFilesToLibraryPattern ?? false;
    }

    this.presetOptions = [
      {label: this.t.translate('book.duplicateMerger.presetStrict'), value: 'strict'},
      {label: this.t.translate('book.duplicateMerger.presetBalanced'), value: 'balanced'},
      {label: this.t.translate('book.duplicateMerger.presetAggressive'), value: 'aggressive'},
      {label: this.t.translate('book.duplicateMerger.presetCustom'), value: 'custom'},
    ];
    this.applyPreset('balanced');
  }

  ngOnDestroy(): void {
    this.disposed = true; this.stopRequested.set(true);
    this.destroy$.next();
    this.destroy$.complete();
  }

  onPresetChange(): void {
    if (this.presetMode !== 'custom') {
      this.applyPreset(this.presetMode);
    }
  }

  onSignalToggle(): void {
    this.presetMode = 'custom';
  }

  applyPreset(mode: PresetMode): void {
    switch (mode) {
      case 'strict':
        this.matchByIsbn = true;
        this.matchByExternalId = true;
        this.matchByTitleAuthor = false;
        this.matchByDirectory = false;
        this.matchByFilename = false;
        break;
      case 'balanced':
        this.matchByIsbn = true;
        this.matchByExternalId = true;
        this.matchByTitleAuthor = true;
        this.matchByDirectory = false;
        this.matchByFilename = false;
        break;
      case 'aggressive':
        this.matchByIsbn = true;
        this.matchByExternalId = true;
        this.matchByTitleAuthor = true;
        this.matchByDirectory = true;
        this.matchByFilename = true;
        break;
    }
  }

  scan(): void {
    this.isScanning.set(true);
    this.hasScanned.set(false);
    this.groups = [];
    this.pageFirst = 0;

    const request: DuplicateDetectionRequest = {
      libraryId: this.libraryId,
      matchByIsbn: this.matchByIsbn,
      matchByExternalId: this.matchByExternalId,
      matchByTitleAuthor: this.matchByTitleAuthor,
      matchByDirectory: this.matchByDirectory,
      matchByFilename: this.matchByFilename,
    };

    this.bookFileService.findDuplicates(request).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (groups) => {
        this.groups = groups.map(g => ({
          ...g,
          selectedTargetBookId: g.suggestedTargetBookId,
          dismissed: false,
          selectedForDeletion: new Set<number>(),
        }));
        this.isScanning.set(false);
        this.hasScanned.set(true);
      },
      error: (err) => {
        this.isScanning.set(false);
        this.hasScanned.set(true);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('book.duplicateMerger.toast.scanFailedSummary'),
          detail: err?.error?.message || this.t.translate('book.duplicateMerger.toast.scanFailedDetail'),
        });
      }
    });
  }

  get activeGroups(): DisplayGroup[] {
    return this.groups.filter(g => !g.dismissed);
  }

  get pagedGroups(): DisplayGroup[] {
    return this.activeGroups.slice(this.pageFirst, this.pageFirst + this.pageSize);
  }

  get canScan(): boolean {
    return !this.isScanning() && !this.isMerging() &&
      (this.matchByIsbn || this.matchByExternalId || this.matchByTitleAuthor ||
        this.matchByDirectory || this.matchByFilename);
  }

  onPageChange(event: PaginatorState): void {
    this.pageFirst = event.first ?? 0;
    this.pageSize = event.rows ?? this.pageSize;
  }

  getBookFormats(book: Book): string[] {
    const formats: string[] = [];
    if (book.primaryFile?.bookType) {
      formats.push(book.primaryFile.bookType);
    }
    if (book.alternativeFormats) {
      for (const alt of book.alternativeFormats) {
        if (alt.bookType) {
          formats.push(alt.bookType);
        }
      }
    }
    return formats;
  }

  getFileCount(book: Book): number {
    let count = book.primaryFile ? 1 : 0;
    count += book.alternativeFormats?.length ?? 0;
    return count;
  }

  getMatchReasonLabel(reason: string): string {
    return this.t.translate(`book.duplicateMerger.reason.${reason}`);
  }

  hasSameFormatConflict(group: DisplayGroup): boolean {
    const formats = new Set<string>();
    for (const book of group.books) {
      if (book.primaryFile?.bookType) {
        if (formats.has(book.primaryFile.bookType)) return true;
        formats.add(book.primaryFile.bookType);
      }
    }
    return false;
  }

  getBookFilePath(book: Book): string {
    const subPath = book.primaryFile?.fileSubPath;
    const fileName = book.primaryFile?.fileName || '';
    if (subPath) return `${subPath}/${fileName}`;
    return fileName;
  }

  formatFileSize(sizeKb?: number): string {
    if (!sizeKb) return '';
    if (sizeKb < 1024) return `${sizeKb} KB`;
    const sizeMb = sizeKb / 1024;
    if (sizeMb < 1024) return `${sizeMb.toFixed(1)} MB`;
    return `${(sizeMb / 1024).toFixed(2)} GB`;
  }

  getMatchReasonSeverity(reason: string): "success" | "info" | "warn" | "danger" | "secondary" | "contrast" {
    switch (reason) {
      case 'ISBN':
      case 'EXTERNAL_ID':
        return 'success';
      case 'TITLE_AUTHOR':
        return 'info';
      case 'DIRECTORY':
        return 'warn';
      case 'FILENAME':
        return 'secondary';
      default:
        return 'info';
    }
  }

  onTargetChange(group: DisplayGroup): void {
    group.reviewAssessment = undefined; group.error = undefined;
    group.selectedForDeletion.delete(group.selectedTargetBookId);
  }

  toggleDeleteSelection(group: DisplayGroup, bookId: number): void {
    if (group.selectedForDeletion.has(bookId)) {
      group.selectedForDeletion.delete(bookId);
    } else {
      group.selectedForDeletion.add(bookId);
    }
  }

  getDeleteSelectedCount(group: DisplayGroup): number {
    return group.selectedForDeletion.size;
  }

  dismissGroup(group: DisplayGroup): void {
    group.dismissed = true;
    if (this.pagedGroups.length === 0 && this.pageFirst > 0) {
      this.pageFirst = Math.max(0, this.pageFirst - this.pageSize);
    }
  }

  private catalogueGroup(group: DisplayGroup): CatalogueGroup {
    return {key: group.books.map(book => book.id).sort((a,b) => a-b).join('-'), path: this.getBookFilePath(group.books[0]),
      suggestedTargetBookId: group.selectedTargetBookId,
      members: group.books.map(book => ({bookId:book.id,fileId:0,title:book.metadata?.title || '',authors:[],storedHash:null}))};
  }

  async mergeGroup(group: DisplayGroup): Promise<void> {
    if (this.isMerging() || this.catalogueMerging || this.disposed) return;
    if (group.reviewAssessment) return;
    group.error = undefined;
    this.isMerging.set(true);
    try {
      const result = await lastValueFrom(this.reconciliation.quickMerge(this.libraryId, this.catalogueGroup(group), group.selectedTargetBookId).pipe(takeUntil(this.destroy$)));
      if (result.status === 'MERGED' && result.receipt) {
        this.onReconciled(result.receipt);
        this.messageService.add({severity:'success',summary:this.t.translate('book.catalogueReconciliation.quickMerged'),detail:this.t.translate('book.catalogueReconciliation.receipt',{id:result.receipt.id})});
      } else {group.reviewAssessment = result;}
    } catch (err: any) {
      group.error = err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed');
      this.messageService.add({severity:'error',summary:this.t.translate('book.catalogueReconciliation.operationFailed'),detail:group.error});
    } finally {this.isMerging.set(false);}
  }

  reviewGroup(group: DisplayGroup): void {
    this.reviewedGroupFinished = false;
    this.reviewAssessment = group.reviewAssessment ?? null;
    this.reviewedGroup = {
      key: group.books.map(book => book.id).sort((a, b) => a - b).join('-'),
      path: this.getBookFilePath(group.books[0]),
      suggestedTargetBookId: group.selectedTargetBookId,
      members: group.books.map(book => ({bookId: book.id, fileId: 0, title: book.metadata?.title || '', authors: [], storedHash: null})),
    };
    this.mode = 'catalogue';
  }

  reviewNextGroup(): void {
    const first = this.activeGroups.find(group => group.reviewAssessment || group.error) ?? this.pagedGroups[0] ?? this.activeGroups[0];
    if (first) this.reviewGroup(first);
  }

  mergeAll(): void {
    if (this.isMerging() || this.catalogueMerging || !this.activeGroups.length) return;
    const queue = [...this.activeGroups];
    this.confirmationService.confirm({
      header:this.t.translate('book.catalogueReconciliation.batchTitle'),
      message:this.t.translate('book.catalogueReconciliation.batchConfirm',{count:queue.length}),
      acceptLabel:this.t.translate('book.catalogueReconciliation.mergeSimple'),rejectLabel:this.t.translate('common.no'),
      accept:() => {void this.runBatch(queue);},
    });
  }

  stopBatch(): void {this.stopRequested.set(true);}

  private async runBatch(queue: DisplayGroup[]): Promise<void> {
    if (this.isMerging() || this.disposed) return;
    this.isMerging.set(true); this.batchRunning.set(true); this.stopRequested.set(false);
    this.mergeTotal.set(queue.length); this.mergeProgress.set(0);
    let merged=0,review=0,failed=0;
    try {
      for (const group of queue) {
        if (this.stopRequested() || this.disposed) break;
        if (!group.dismissed) {
          if (group.reviewAssessment || group.error) review++;
          else try {
            const result=await lastValueFrom(this.reconciliation.quickMerge(this.libraryId,this.catalogueGroup(group),group.selectedTargetBookId).pipe(takeUntil(this.destroy$)));
            if (result.status === 'MERGED' && result.receipt) {this.onReconciled(result.receipt);merged++;}
            else {group.reviewAssessment=result;review++;}
          } catch (err: any) {
            group.error=err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed');failed++;
            if (!err?.status || err.status>=500 || err.status===401 || err.status===403) this.stopRequested.set(true);
          }
        }
        this.mergeProgress.update(value=>value+1);
      }
    } finally {
      this.batchRunning.set(false); this.isMerging.set(false);
      if (!this.disposed) this.messageService.add({severity:failed?'warn':'success',summary:this.t.translate('book.catalogueReconciliation.batchFinished'),
        detail:this.t.translate('book.catalogueReconciliation.batchSummary',{merged,review,failed})});
    }
  }

  onReconciled(receipt: CatalogueReceipt): void {
    this.reviewedGroupFinished = true;
    this.resolvedBookIds = [...new Set([...this.resolvedBookIds, receipt.targetBookId, ...receipt.removedBookIds])];
    const changed = new Set([receipt.targetBookId, ...receipt.removedBookIds]);
    for (const group of this.groups) {
      if (group.books.some(book => changed.has(book.id))) group.dismissed = true;
    }
    if (this.pagedGroups.length === 0 && this.pageFirst > 0) this.pageFirst = Math.max(0, this.pageFirst - this.pageSize);
  }

  async notDuplicates(group: DisplayGroup, isolatedBookId?: number): Promise<void> {
    if (this.isMerging() || this.catalogueMerging) return;
    this.isMerging.set(true);
    try {
      const ids=group.books.map(book=>book.id);
      await lastValueFrom(this.reconciliation.notDuplicates(this.libraryId,ids,isolatedBookId).pipe(takeUntil(this.destroy$)));
      if(isolatedBookId==null)this.onExcluded(ids);else this.onSeparated(isolatedBookId);
    } catch(err:any) {group.error=err?.error?.message || this.t.translate('book.catalogueReconciliation.operationFailed');}
    finally {this.isMerging.set(false);}
  }

  onSeparated(bookId: number): void {
    this.separatedBookIds=[...new Set([...this.separatedBookIds,bookId])];
    for(const group of this.groups) if(group.books.some(book=>book.id===bookId)) {
      group.books=group.books.filter(book=>book.id!==bookId);group.reviewAssessment=undefined;group.error=undefined;
      group.selectedForDeletion.delete(bookId);
      if(group.selectedTargetBookId===bookId&&group.books.length)group.selectedTargetBookId=group.books[0].id;
      if(group.books.length<2)group.dismissed=true;
    }
    this.reviewedGroup=null;this.reviewAssessment=null;
  }

  onExcluded(ids: number[]): void {
    this.resolvedBookIds=[...new Set([...this.resolvedBookIds,...ids])];
    for (const group of this.groups) if (group.books.some(book=>ids.includes(book.id))) group.dismissed=true;
    this.reviewedGroup=null;this.reviewAssessment=null;
  }

  deleteGroup(group: DisplayGroup): void {
    const idsToDelete = Array.from(group.selectedForDeletion);
    if (idsToDelete.length === 0) return;

    this.confirmationService.confirm({
      message: this.t.translate('book.duplicateMerger.confirm.deleteMessage', {count: idsToDelete.length}),
      header: this.t.translate('book.duplicateMerger.confirm.deleteHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.t.translate('common.yes'),
      rejectLabel: this.t.translate('common.no'),
      acceptButtonProps: {severity: 'danger'},
      accept: () => {
        this.bookService.deleteBooks(new Set(idsToDelete)).pipe(
          takeUntil(this.destroy$)
        ).subscribe({
          next: () => {
            group.books = group.books.filter(b => !group.selectedForDeletion.has(b.id));
            group.selectedForDeletion.clear();
            if (group.books.length <= 1) {
              group.dismissed = true;
            }
            this.changeDetectorRef.markForCheck();
          }
        });
      }
    });
  }

  closeDialog(): void {
    if (this.catalogueMerging || this.isMerging()) return;
    this.dialogRef.close();
  }
}
