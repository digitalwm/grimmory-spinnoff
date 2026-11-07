import {ChangeDetectorRef, signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {DynamicDialogConfig, DynamicDialogRef} from '@openng/optimus-ui/dynamicdialog';
import {ConfirmationService, MessageService} from '@openng/optimus-ui/api';
import {TranslocoService} from '@jsverse/transloco';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {Book, DuplicateGroup} from '../../model/book.model';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {UrlHelperService} from '../../../../shared/service/url-helper.service';
import {BookFileService} from '../../service/book-file.service';
import {BookService} from '../../service/book.service';
import {DuplicateMergerComponent} from './duplicate-merger.component';

const createBook = (id: number): Book => ({id, libraryId: 1, libraryName: 'Main Library'});

const createGroup = (books: Book[]): DuplicateGroup => ({
  suggestedTargetBookId: books[0].id,
  matchReason: 'TITLE_AUTHOR',
  books,
});

describe('DuplicateMergerComponent', () => {
  const changeDetectorRef = {markForCheck: vi.fn()};
  const dialogConfig = {data: {libraryId: 1}};
  const bookFileService = {findDuplicates: vi.fn()};
  const bookService = {deleteBooks: vi.fn()};
  const confirmationService = {confirm: vi.fn()};
  const messageService = {add: vi.fn()};
  const translocoService = {translate: vi.fn((key: string) => key)};
  const urlHelper = {getThumbnailUrl: vi.fn()};
  const appSettingsService = {appSettings: signal(null)};

  let component: DuplicateMergerComponent;

  beforeEach(() => {
    vi.restoreAllMocks();
    appSettingsService.appSettings.set(null);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        {provide: ChangeDetectorRef, useValue: changeDetectorRef},
        {provide: DynamicDialogConfig, useValue: dialogConfig},
        {provide: DynamicDialogRef, useValue: {close: vi.fn()}},
        {provide: BookFileService, useValue: bookFileService},
        {provide: BookService, useValue: bookService},
        {provide: ConfirmationService, useValue: confirmationService},
        {provide: MessageService, useValue: messageService},
        {provide: TranslocoService, useValue: translocoService},
        {provide: UrlHelperService, useValue: urlHelper},
        {provide: AppSettingsService, useValue: appSettingsService},
      ]
    });

    component = TestBed.runInInjectionContext(() => new DuplicateMergerComponent());
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('removes a deleted book from its group, dismisses the group, and notifies change detection', () => {
    const deletedBookId = 2;

    bookFileService.findDuplicates.mockReturnValue(of([
      createGroup([createBook(1), createBook(2)]),
    ]));
    component.ngOnInit();
    component.scan();

    const group = component.groups[0];
    component.toggleDeleteSelection(group, deletedBookId);

    bookService.deleteBooks.mockReturnValue(of({deleted: [deletedBookId], failedFileDeletions: []}));
    confirmationService.confirm.mockImplementation(config => config.accept());

    component.deleteGroup(group);

    expect(group.books.map(book => book.id)).toEqual([1]);
    expect(group.dismissed).toBe(true);
    expect(changeDetectorRef.markForCheck).toHaveBeenCalledOnce();
  });
  it('routes a mixed group to one reviewed preview without attaching or deleting anything', () => {
    component.ngOnInit(); const group = {...createGroup([createBook(1),createBook(2),createBook(3)]),
      selectedTargetBookId: 2,dismissed: false,selectedForDeletion:new Set<number>()};
    component.mergeGroup(group);
    expect(component.mode).toBe('catalogue'); expect(component.reviewedGroup?.suggestedTargetBookId).toBe(2);
    expect(component.reviewedGroup?.members.map(member=>member.bookId)).toEqual([1,2,3]);
    expect(group.dismissed).toBe(false);
  });
  it('reviews the next group without performing an automatic bulk merge', () => {
    component.ngOnInit(); component.groups=[{...createGroup([createBook(1),createBook(2)]),
      selectedTargetBookId:1,dismissed:false,selectedForDeletion:new Set<number>()}];
    component.mergeAll(); expect(component.mode).toBe('catalogue'); expect(component.reviewedGroup?.members.length).toBe(2);
    expect(component.groups[0].dismissed).toBe(false);
  });

  it('keeps unaffected groups, selections and the completed scan after reconciliation', () => {
    component.ngOnInit();const first={...createGroup([createBook(1),createBook(2)]),selectedTargetBookId:1,dismissed:false,selectedForDeletion:new Set<number>()};
    const second={...createGroup([createBook(3),createBook(4)]),selectedTargetBookId:3,dismissed:false,selectedForDeletion:new Set([4])};
    component.groups=[first,second];component.hasScanned.set(true);
    component.onReconciled({id:'receipt',targetBookId:1,removedBookIds:[2],fileSha256:'verified',completedAt:'now'});
    expect(component.hasScanned()).toBe(true);expect(first.dismissed).toBe(true);
    expect(component.activeGroups).toEqual([second]);expect(second.selectedForDeletion.has(4)).toBe(true);
  });

  it('reviews the next remaining group on the current page instead of jumping back to page one', () => {
    component.ngOnInit();component.groups=[1,3,5].map(id=>({...createGroup([createBook(id),createBook(id+1)]),selectedTargetBookId:id,dismissed:false,selectedForDeletion:new Set<number>()}));
    component.pageSize=1;component.pageFirst=1;
    component.onReconciled({id:'receipt',targetBookId:3,removedBookIds:[4],fileSha256:'verified',completedAt:'now'});
    component.mergeAll();expect(component.reviewedGroup?.members.map(member=>member.bookId)).toEqual([5,6]);
    expect(component.pageFirst).toBe(1);
  });

});
