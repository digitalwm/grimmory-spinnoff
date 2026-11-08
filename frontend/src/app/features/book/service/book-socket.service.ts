import {DestroyRef, inject, Service} from '@angular/core';
import {QueryClient} from '@tanstack/angular-query-experimental';

import {invalidateBookRecommendations} from '../data/book-query-cache';
import {TaskProgressPayload, TaskStatus} from '../../settings/task-management/task.service';
import {Book} from '../model/book.model';
import {
  BookCoverPatch,
  invalidateAllBookCaches,
  invalidateLegacyBookRecommendations,
  patchBooksInCache,
  patchBookCoversInCache,
  reconcileBookCacheChangeSet,
  upsertBooksInCache,
} from './legacy-book-cache';

function isBookIdArray(payload: Book | readonly number[]): payload is readonly number[] {
  return Array.isArray(payload);
}
@Service()
export class BookSocketService {
  private readonly queryClient = inject(QueryClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly reconciliationDelayMs = 50;
  private readonly pendingChangedBookIds = new Set<number>();
  private readonly pendingDeletedBookIds = new Set<number>();
  private batchDepth = 0;
  private fullRefreshPending = false;
  private readonly catalogueDeletedIds = new Set<number>();
  private reconciliationTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.clearPendingReconciliation());
  }

  beginBatch(): void {
    this.batchDepth++;
    if(this.reconciliationTimer!==null){clearTimeout(this.reconciliationTimer);this.reconciliationTimer=null;}
  }

  endBatch(): void {
    this.batchDepth=Math.max(0,this.batchDepth-1);
    if(this.batchDepth)return;
    if(this.fullRefreshPending){this.fullRefreshPending=false;this.clearPendingReconciliation();invalidateAllBookCaches(this.queryClient);}
    else if(this.pendingChangedBookIds.size||this.pendingDeletedBookIds.size)this.scheduleReconciliation();
  }

  recordCatalogueMerge(targetId:number, removedIds:readonly number[]): void {
    this.queueDeletedBooks(removedIds);
    for(const id of removedIds)this.catalogueDeletedIds.add(id);
    while(this.catalogueDeletedIds.size>10_000)this.catalogueDeletedIds.delete(this.catalogueDeletedIds.values().next().value!);
    this.queueKnownChanges([targetId]);
  }

  handleNewlyCreatedBook(book: Book): void {
    upsertBooksInCache(this.queryClient, [book]);
  }

  handleRemovedBookIds(bookIds: readonly number[]): void {
    this.queueDeletedBooks(bookIds.filter(id=>!this.catalogueDeletedIds.has(id)));
  }

  handleBookUpdate(payload: Book | readonly number[]): void {
    if (isBookIdArray(payload)) {
      this.queueKnownChanges(payload);
      return;
    }
    patchBooksInCache(this.queryClient, [payload]);
  }

  handleBookMetadataUpdate(bookId: number): void {
    this.queueKnownChanges([bookId]);
  }

  handleMultipleBookCoverPatches(patches: readonly BookCoverPatch[]): void {
    patchBookCoversInCache(this.queryClient, patches);
  }

  handleTaskProgress(payload: TaskProgressPayload): void {
    if (payload.taskType !== 'UPDATE_BOOK_RECOMMENDATIONS' || payload.taskStatus !== TaskStatus.COMPLETED) {
      return;
    }
    void Promise.all([
      invalidateBookRecommendations(this.queryClient),
      invalidateLegacyBookRecommendations(this.queryClient),
    ]);
  }

  handleReconnect(): void {
    if(this.batchDepth){this.fullRefreshPending=true;return;}
    this.clearPendingReconciliation();
    invalidateAllBookCaches(this.queryClient);
  }

  private queueKnownChanges(bookIds: readonly number[]): void {
    for (const bookId of bookIds) {
      if (!this.pendingDeletedBookIds.has(bookId)) {
        this.pendingChangedBookIds.add(bookId);
      }
    }
    this.scheduleReconciliation();
  }

  private queueDeletedBooks(bookIds: readonly number[]): void {
    if(!bookIds.length)return;
    for (const bookId of bookIds) {
      this.pendingChangedBookIds.delete(bookId);
      this.pendingDeletedBookIds.add(bookId);
    }
    this.scheduleReconciliation();
  }

  private scheduleReconciliation(): void {
    if (this.batchDepth || this.reconciliationTimer !== null) {
      return;
    }

    this.reconciliationTimer = setTimeout(() => this.flushPendingReconciliation(), this.reconciliationDelayMs);
  }

  private flushPendingReconciliation(): void {
    this.reconciliationTimer = null;
    const changedBookIds = [...this.pendingChangedBookIds];
    const deletedBookIds = [...this.pendingDeletedBookIds];
    this.pendingChangedBookIds.clear();
    this.pendingDeletedBookIds.clear();

    void reconcileBookCacheChangeSet(
      this.queryClient,
      {changedBookIds, deletedBookIds},
      {legacyList: 'needs-refetch'},
    );
  }

  private clearPendingReconciliation(): void {
    if (this.reconciliationTimer !== null) {
      clearTimeout(this.reconciliationTimer);
      this.reconciliationTimer = null;
    }
    this.pendingChangedBookIds.clear();
    this.pendingDeletedBookIds.clear();
  }
}
