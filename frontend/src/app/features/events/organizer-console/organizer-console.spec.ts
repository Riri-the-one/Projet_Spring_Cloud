import { ComponentFixture, TestBed } from '@angular/core/testing';

import { OrganizerConsole } from './organizer-console';

describe('OrganizerConsole', () => {
  let component: OrganizerConsole;
  let fixture: ComponentFixture<OrganizerConsole>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrganizerConsole],
    }).compileComponents();

    fixture = TestBed.createComponent(OrganizerConsole);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
