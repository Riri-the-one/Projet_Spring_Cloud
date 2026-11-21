import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';

interface CreatedEvent {
  id: number;
  title: string;
  status: string;
  description: string | null;
  venue: string;
  startsAt: string;
  price: number;
  capacity: number;
  categorie: string | null;
}

@Component({
  selector: 'app-organizer-console',
  templateUrl: './organizer-console.html',
  styleUrl: './organizer-console.css',
})
export class OrganizerConsole implements OnInit {
  private readonly http = inject(HttpClient);

  readonly saving = signal(false);
  readonly error = signal('');
  readonly createdEvent = signal<CreatedEvent | null>(null);
  readonly editingEvent = signal<CreatedEvent | null>(null);
  readonly myEvents = signal<CreatedEvent[]>([]);
  readonly myEventsLoading = signal(true);
  readonly myEventsError = signal('');

  ngOnInit(): void {
    this.http.get<CreatedEvent[]>('/api/events/mine')
      .pipe(finalize(() => this.myEventsLoading.set(false)))
      .subscribe({
        next: (events) => this.myEvents.set(events),
        error: (response) => {
          this.myEventsError.set(`Impossible de charger tes événements (HTTP ${response.status}).`);
        },
      });
  }
  edit(event: CreatedEvent): void {
    this.error.set('');
    this.editingEvent.set(event);
  }

  localDateTime(value: string): string {
    const date = new Date(value);
    const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
    return local.toISOString().slice(0, 16);
  }
  create(event: Event, form: HTMLFormElement): void {
    event.preventDefault();

    if (this.saving() || !form.reportValidity()) {
      return;
    }

    const editing = this.editingEvent();
    const fields = new FormData(form);
    const enteredDate = String(fields.get('startsAt') ?? '');
    const startsAt = new Date(enteredDate);

    this.error.set('');

    if (!Number.isFinite(startsAt.getTime()) || startsAt.getTime() <= Date.now()) {
      this.error.set('Choisis une date et une heure dans le futur.');
      return;
    }

    const payload = {
      title: String(fields.get('title') ?? '').trim(),
      description: String(fields.get('description') ?? '').trim(),
      venue: String(fields.get('venue') ?? '').trim(),
      startsAt: editing && enteredDate === this.localDateTime(editing.startsAt)
        ? editing.startsAt
        : startsAt.toISOString(),
      price: Number(fields.get('price')),
      capacity: Number(fields.get('capacity')),
      categorie: editing?.categorie ?? null,
    };

    const request = editing
      ? this.http.put<CreatedEvent>(`/api/events/${editing.id}`, payload)
      : this.http.post<CreatedEvent>('/api/events', payload);

    this.saving.set(true);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.createdEvent.set(saved);
        this.myEvents.update((events) => editing
          ? events.map((item) => item.id === saved.id ? saved : item)
          : [...events, saved]);
        this.editingEvent.set(null);
        form.reset();
      },
      error: (response) => {
        this.error.set(`Enregistrement impossible (HTTP ${response.status}).`);
      },
    });
  }
  publish(id: number): void {
    if (this.saving()) {
      return;
    }

    this.error.set('');
    this.saving.set(true);

    this.http.post<CreatedEvent>(`/api/events/${id}/publish`, null)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (published) => {
          this.createdEvent.set(published);
          this.myEvents.update((events) =>
            events.map((event) => event.id === published.id ? published : event),
          );
        },
        error: (response) => {
          this.error.set(`Publication impossible (HTTP ${response.status}).`);
        },
      });
  }
  close(id: number): void {
    if (this.saving()) {
      return;
    }

    this.error.set('');
    this.saving.set(true);

    this.http.post<CreatedEvent>(`/api/events/${id}/close`, null)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (closed) => {
          this.createdEvent.set(closed);
          this.myEvents.update((events) =>
            events.map((event) => event.id === closed.id ? closed : event),
          );
        },
        error: (response) => {
          this.error.set(`Fermeture impossible (HTTP ${response.status}).`);
        },
      });
  }
}
